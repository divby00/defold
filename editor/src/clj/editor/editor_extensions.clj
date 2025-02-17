;; Copyright 2020-2024 The Defold Foundation
;; Copyright 2014-2020 King
;; Copyright 2009-2014 Ragnar Svensson, Christian Murray
;; Licensed under the Defold License version 1.0 (the "License"); you may not use
;; this file except in compliance with the License.
;;
;; You may obtain a copy of the License, together with FAQs at
;; https://www.defold.com/license
;;
;; Unless required by applicable law or agreed to in writing, software distributed
;; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
;; CONDITIONS OF ANY KIND, either express or implied. See the License for the
;; specific language governing permissions and limitations under the License.

(ns editor.editor-extensions
  (:require [cljfx.api :as fx]
            [clojure.set :as set]
            [clojure.stacktrace :as stacktrace]
            [clojure.string :as string]
            [dynamo.graph :as g]
            [editor.code.data :as data]
            [editor.console :as console]
            [editor.defold-project :as project]
            [editor.editor-extensions.actions :as actions]
            [editor.editor-extensions.commands :as commands]
            [editor.editor-extensions.error-handling :as error-handling]
            [editor.editor-extensions.graph :as graph]
            [editor.editor-extensions.runtime :as rt]
            [editor.editor-extensions.validation :as validation]
            [editor.fs :as fs]
            [editor.future :as future]
            [editor.graph-util :as gu]
            [editor.handler :as handler]
            [editor.lsp :as lsp]
            [editor.lsp.async :as lsp.async]
            [editor.process :as process]
            [editor.system :as system]
            [editor.util :as util]
            [editor.workspace :as workspace])
  (:import [com.dynamo.bob Platform]
           [java.nio.file FileAlreadyExistsException Files NotDirectoryException Path]
           [org.luaj.vm2 LuaError Prototype]))

(set! *warn-on-reflection* true)

(defn- ext-state
  "Returns an extension state, a map with following keys:
    :reload-resources!     0-arg function used to reload resources
    :display-output!       2-arg function used to display extension-related
                           output to the user, where args are:
                             type    output type, :err or :out
                             msg     string message, may be multiline
    :project-prototypes    vector of project-owned editor script Prototypes
    :library-prototypes    vector of library-provided editor script Prototypes
    :rt                    editor script runtime
    :all                   map of module function keyword to a vector of tuples:
                             path      proj-path of an editor script
                             lua-fn    LuaFunction identified by the keyword
    :hooks                 exists only when '/hooks.editor_script' exists, a map
                           from module function keyword to LuaFunction"
  [project evaluation-context]
  (-> project
      (g/node-value :editor-extensions evaluation-context)
      (g/user-data :state)))

(defn- execute-all-top-level-functions
  "Returns reducible that executes all specified top-level editor script fns

  Args:
    state         editor extensions state
    fn-keyword    keyword identifying the editor script function
    opts          Clojure data structure that will be coerced to Lua

  Returns a vector of path+ret tuples, removing all results that threw
  exception, where:
    path    a proj-path of the editor script, string
    ret     a Clojure data structure returned from function in that file"
  [state fn-keyword opts evaluation-context]
  (let [{:keys [rt all display-output!]} state
        lua-opts (rt/->lua opts)
        label (name fn-keyword)]
    (eduction
      (keep (fn [[path lua-fn]]
              (when-let [ret (error-handling/try-with-extension-exceptions
                               :display-output! display-output!
                               :label (str label " in " path)
                               :catch nil
                               (rt/->clj rt (rt/invoke-immediate rt lua-fn lua-opts evaluation-context)))]
                [path ret])))
      (get all fn-keyword))))

(defn- unwrap-error-values [arr]
  (mapv #(cond-> % (g/error? %) :value) arr))

(g/defnode EditorExtensions
  (input project-prototypes g/Any :array :substitute unwrap-error-values)
  (input library-prototypes g/Any :array :substitute unwrap-error-values)
  (output project-prototypes g/Any (gu/passthrough project-prototypes))
  (output library-prototypes g/Any (gu/passthrough library-prototypes)))

(defn make [graph]
  (first (g/tx-nodes-added (g/transact (g/make-node graph EditorExtensions)))))

;; region script API

(defn- make-ext-get-fn [project]
  (rt/lua-fn ext-get [{:keys [rt evaluation-context]} lua-node-id-or-path lua-property]
    (let [node-id-or-path (validation/ensure ::validation/node-id-or-path (rt/->clj rt lua-node-id-or-path))
          property (validation/ensure string? (rt/->clj rt lua-property))
          node-id (graph/node-id-or-path->node-id node-id-or-path project evaluation-context)
          getter (graph/ext-value-getter node-id property evaluation-context)]
      (if getter
        (getter)
        (throw (LuaError. (str (name (graph/node-id->type-keyword node-id evaluation-context))
                               " has no \""
                               property
                               "\" property")))))))

(defn- make-ext-can-get-fn [project]
  (rt/lua-fn ext-can-get [{:keys [rt evaluation-context]} lua-node-id-or-path lua-property]
    (let [node-id-or-path (validation/ensure ::validation/node-id-or-path (rt/->clj rt lua-node-id-or-path))
          property (validation/ensure string? (rt/->clj rt lua-property))
          node-id (graph/node-id-or-path->node-id node-id-or-path project evaluation-context)]
      (some? (graph/ext-value-getter node-id property evaluation-context)))))

(defn- make-ext-can-set-fn [project]
  (rt/lua-fn ext-can-set [{:keys [rt evaluation-context]} lua-node-id-or-path lua-property]
    (let [node-id-or-path (validation/ensure ::validation/node-id-or-path (rt/->clj rt lua-node-id-or-path))
          property (validation/ensure string? (rt/->clj rt lua-property))
          node-id (graph/node-id-or-path->node-id node-id-or-path project evaluation-context)]
      (some? (graph/ext-value-setter node-id property project evaluation-context)))))

(defn- make-ext-create-directory-fn [project reload-resources!]
  (rt/suspendable-lua-fn ext-create-directory [{:keys [rt evaluation-context]} lua-proj-path]
    (let [^String proj-path (rt/->clj rt lua-proj-path)]
      (validation/ensure validation/resource-path? proj-path)
      (let [root-path (-> project
                          (project/workspace evaluation-context)
                          (workspace/project-path evaluation-context)
                          (fs/as-path)
                          (fs/to-real-path))
            dir-path (-> (str root-path proj-path)
                         (fs/as-path)
                         (.normalize))]
        (if (.startsWith dir-path root-path)
          (try
            (fs/create-path-directories! dir-path)
            (future/then (reload-resources!) rt/and-refresh-context)
            (catch FileAlreadyExistsException e
              (throw (LuaError. (str "File already exists: " (.getMessage e)))))
            (catch Exception e
              (throw (LuaError. ^String (or (.getMessage e) (.getSimpleName (class e)))))))
          (throw (LuaError. (str "Can't create " dir-path ": outside of project directory"))))))))

(defn- make-ext-delete-directory-fn [project reload-resources!]
  (rt/suspendable-lua-fn ext-delete-directory [{:keys [rt evaluation-context]} lua-proj-path]
    (let [proj-path (validation/ensure validation/resource-path? (rt/->clj rt lua-proj-path))
          root-path (-> project
                        (project/workspace evaluation-context)
                        (workspace/project-path evaluation-context)
                        (fs/as-path)
                        (fs/to-real-path))
          dir-path (-> (str root-path proj-path)
                       (fs/as-path)
                       (.normalize))
          protected-paths (mapv #(.resolve root-path ^String %)
                                [".git"
                                 ".internal"])
          protected-path? (fn protected-path? [^Path path]
                            (some #(.startsWith path ^Path %)
                                  protected-paths))]
      (cond
        (not (.startsWith dir-path root-path))
        (throw (LuaError. (str "Can't delete " dir-path ": outside of project directory")))

        (= (.getNameCount dir-path) (.getNameCount root-path))
        (throw (LuaError. (str "Can't delete the project directory itself")))

        (protected-path? dir-path)
        (throw (LuaError. (str "Can't delete " dir-path ": protected by editor")))

        :else
        (try
          (when (fs/delete-path-directory! dir-path)
            (future/then (reload-resources!) rt/and-refresh-context))
          (catch NotDirectoryException e
            (throw (LuaError. (str "Not a directory: " (.getMessage e)))))
          (catch Exception e
            (throw (LuaError. (str (.getMessage e))))))))))

(def ^:private empty-lua-string
  (rt/->lua ""))

(defn- make-ext-execute-fn [^Path project-path display-output! reload-resources!]
  (rt/suspendable-lua-fn ext-execute [{:keys [rt]} & lua-args]
    (when (empty? lua-args)
      (throw (LuaError. "No arguments provided to editor.execute()")))
    (let [args (mapv #(rt/->clj rt %) lua-args)
          last-arg (peek args)
          options-provided (map? last-arg)
          cmd+args (validation/ensure ::validation/execute-command
                     (if options-provided (pop args) args))
          options (validation/ensure ::validation/execute-options
                    (if options-provided last-arg {}))
          err (:err options "pipe")
          out (:out options "pipe")
          reload (:reload_resources options true)
          ^Process p (apply process/start!
                            {:dir (.toFile project-path)
                             :out (case out
                                    ("pipe" "capture") :pipe
                                    "discard" :discard)
                             :err (case err
                                    "pipe" :pipe
                                    "discard" :discard
                                    "stdout" :stdout)}
                            cmd+args)
          maybe-output-future (when (= "capture" out)
                                (future/supply-async
                                  (or (process/capture! (process/out p))
                                      empty-lua-string)))]
      (when (= "pipe" out)
        (actions/input-stream->console (process/out p) display-output! :out))
      (when (= "pipe" err)
        (actions/input-stream->console (process/err p) display-output! :err))
      (-> (.onExit p)
          (future/then
            (fn [_]
              (let [exit-code (.exitValue p)]
                (when-not (zero? exit-code)
                  (throw (LuaError. (format "Command \"%s\" exited with code %s"
                                            (string/join " " cmd+args)
                                            exit-code)))))))
          (cond-> maybe-output-future
                  (future/then (fn [_] maybe-output-future))

                  reload
                  (future/then
                    (fn [result]
                      (future/then
                        (reload-resources!)
                        (fn [_] (rt/and-refresh-context result))))))))))

(defn- ensure-file-path-in-project-directory
  ^Path [^Path project-path ^String file-name]
  (let [normalized-path (.normalize (.resolve project-path file-name))]
    (if (.startsWith normalized-path project-path)
      normalized-path
      (throw (LuaError. (str "Can't access " file-name ": outside of project directory"))))))

(defn- make-ext-remove-file-fn [project-path reload-resources!]
  (rt/suspendable-lua-fn ext-remove-file [{:keys [rt]} lua-file-name]
    (let [file-name (validation/ensure string? (rt/->clj rt lua-file-name))
          file-path (ensure-file-path-in-project-directory project-path file-name)]
      (when-not (Files/exists file-path fs/empty-link-option-array)
        (throw (LuaError. (str "No such file or directory: " file-name))))
      (Files/delete file-path)
      (future/then (reload-resources!) rt/and-refresh-context))))

(def ^:private ext-transact
  (rt/suspendable-lua-fn ext-transact [{:keys [rt]} lua-txs]
    (let [txs (validation/ensure ::validation/transaction-steps (rt/->clj rt lua-txs))
          f (future/make)
          transact (bound-fn* g/transact)]
      (fx/on-fx-thread
        (try
          (transact txs)
          (future/complete! f (rt/and-refresh-context nil))
          (catch Throwable ex (future/fail! f ex))))
      f)))

(defn- make-ext-tx-set-fn [project]
  (rt/lua-fn ext-tx-set [{:keys [rt evaluation-context]} lua-node-id-or-path lua-property lua-value]
    (let [node-id (graph/node-id-or-path->node-id
                    (validation/ensure
                      ::validation/node-id-or-path
                      (rt/->clj rt lua-node-id-or-path))
                    project
                    evaluation-context)
          property (validation/ensure string? (rt/->clj rt lua-property))
          value (rt/->clj rt lua-value)
          setter (graph/ext-value-setter node-id property project evaluation-context)]
      (if setter
        (-> (setter value)
            (with-meta {:type :transaction-step})
            (rt/wrap-userdata "editor.tx.set(...)"))
        (throw (LuaError. (format "Can't set property \"%s\" of %s"
                                  property
                                  (name (graph/node-id->type-keyword node-id evaluation-context)))))))))

(defn- make-ext-save-fn [save!]
  (rt/suspendable-lua-fn ext-save [_]
    (future/then (save!) rt/and-refresh-context)))

;; endregion

;; region language servers

(defn- built-in-language-servers []
  (let [lua-lsp-root (str (system/defold-unpack-path) "/" (.getPair (Platform/getHostPlatform)) "/bin/lsp/lua")]
    #{{:languages #{"lua"}
       :watched-files [{:pattern "**/.luacheckrc"}]
       :launcher {:command [(str lua-lsp-root "/bin/lua-language-server" (when (util/is-win32?) ".exe"))
                            (str "--configpath=" lua-lsp-root "/config.json")]}}}))

(defn- reload-language-servers! [project state evaluation-context]
  (let [lsp (lsp/get-node-lsp project)]
    (lsp/set-servers!
      lsp
      (into
        (built-in-language-servers)
        (comp
          (mapcat
            (fn [[path language-servers]]
              (error-handling/try-with-extension-exceptions
                :display-output! (:display-output! state)
                :label (str "Reloading language servers in " path)
                :catch []
                (validation/ensure ::validation/language-servers language-servers))))
          (map (fn [language-server]
                 (-> language-server
                     (set/rename-keys {:watched_files :watched-files})
                     (update :languages set)
                     (dissoc :command)
                     (assoc :launcher (select-keys language-server [:command]))))))
        (execute-all-top-level-functions state :get_language_servers {} evaluation-context)))))

;; endregion

;; region reload

(defn- reload-commands! [project state evaluation-context]
  (let [{:keys [display-output!]} state]
    (handler/register-dynamic! ::commands
      (into []
            (mapcat
              (fn [[path ret]]
                (error-handling/try-with-extension-exceptions
                  :display-output! display-output!
                  :label (str "Reloading commands in " path)
                  :catch nil
                  (eduction
                    (keep (fn [command]
                            (error-handling/try-with-extension-exceptions
                              :display-output! display-output!
                              :label (str (:label command) " in " path)
                              :catch nil
                              (commands/command->dynamic-handler command path project state))))
                    (validation/ensure ::validation/commands ret)))))
            (execute-all-top-level-functions state :get_commands {} evaluation-context)))))

(defn- add-all-entry [m path module]
  (reduce-kv
    (fn [acc k v]
      (update acc k (fnil conj []) [path v]))
    m
    module))

(def hooks-file-path "/hooks.editor_script")

(defn- re-create-ext-state [initial-state evaluation-context]
  (let [{:keys [rt display-output!]} initial-state]
    (->> [:library-prototypes :project-prototypes]
         (eduction (mapcat initial-state))
         (reduce
           (fn [acc x]
             (cond
               (instance? LuaError x)
               (do
                 (display-output! :err (str "Compilation failed" (some->> (ex-message x) (str ": "))))
                 acc)

               (instance? Prototype x)
               (let [proto-path (.tojstring (.-source ^Prototype x))]
                 (if-let [module (error-handling/try-with-extension-exceptions
                                   :display-output! display-output!
                                   :label (str "Loading " proto-path)
                                   :catch nil
                                   (validation/ensure ::validation/module
                                     (rt/->clj rt (rt/invoke-immediate rt (rt/bind rt x) evaluation-context))))]
                   (-> acc
                       (update :all add-all-entry proto-path module)
                       (cond-> (= hooks-file-path proto-path)
                               (assoc :hooks module)))
                   acc))

               (nil? x)
               acc

               :else
               (throw (ex-info (str "Unexpected prototype value: " x) {:prototype x}))))
           initial-state))))

(defn line-writer [f]
  (let [sb (StringBuilder.)]
    (PrintWriter-on #(doseq [^char ch %]
                       (if (= \newline ch)
                         (let [str (.toString sb)]
                           (.delete sb 0 (.length sb))
                           (f str))
                         (.append sb ch)))
                    nil)))

(defn- find-resource [project {:keys [evaluation-context]} proj-path]
  (when-let [node (project/get-resource-node project proj-path evaluation-context)]
    (data/lines-input-stream (g/node-value node :lines evaluation-context))))

(defn- resolve-file [^Path project-path _ ^String file-name]
  (str (ensure-file-path-in-project-directory project-path file-name)))

;; endregion

;; region public API

(defn reload!
  "Reload the extensions

  Args:
    project    the project node id
    kind       which scripts to reload, either :all, :library or :project

  Required kv-args:
    :reload-resources!    0-arg function that asynchronously reloads the editor
                          resources, returns a CompletableFuture (that might
                          complete exceptionally if reload fails)
    :display-output!      2-arg function used for displaying output in the
                          console, the args are:
                            type    output type, :out or :err
                            msg     a string to output, might be multiline
    :save!                0-arg function that asynchronously saves any unsaved
                          changes, returns CompletableFuture (that might
                          complete exceptionally if reload fails)"
  [project kind & {:keys [reload-resources! display-output! save!] :as opts}]
  (g/with-auto-evaluation-context evaluation-context
    (let [extensions (g/node-value project :editor-extensions evaluation-context)
          old-state (ext-state project evaluation-context)
          project-path (-> project
                           (project/workspace evaluation-context)
                           (workspace/project-path evaluation-context)
                           .toPath
                           .normalize)
          new-state (re-create-ext-state
                      (assoc opts
                        :rt (rt/make
                              :find-resource (partial find-resource project)
                              :resolve-file (partial resolve-file project-path)
                              :close-written (rt/suspendable-lua-fn [_]
                                               (future/then (reload-resources!) rt/and-refresh-context))
                              :out (line-writer #(display-output! :out %))
                              :err (line-writer #(display-output! :err %))
                              :env {"editor" {"get" (make-ext-get-fn project)
                                              "can_get" (make-ext-can-get-fn project)
                                              "can_set" (make-ext-can-set-fn project)
                                              "create_directory" (make-ext-create-directory-fn project reload-resources!)
                                              "delete_directory" (make-ext-delete-directory-fn project reload-resources!)
                                              "execute" (make-ext-execute-fn project-path display-output! reload-resources!)
                                              "platform" (.getPair (Platform/getHostPlatform))
                                              "save" (make-ext-save-fn save!)
                                              "transact" ext-transact
                                              "tx" {"set" (make-ext-tx-set-fn project)}
                                              "version" (system/defold-version)
                                              "engine_sha1" (system/defold-engine-sha1)
                                              "editor_sha1" (system/defold-editor-sha1)}
                                    "io" {"tmpfile" nil}
                                    "os" {"execute" nil
                                          "exit" nil
                                          "remove" (make-ext-remove-file-fn project-path reload-resources!)
                                          "rename" nil
                                          "setlocale" nil
                                          "tmpname" nil}})
                        :library-prototypes (if (or (= :all kind) (= :library kind))
                                              (g/node-value extensions :library-prototypes evaluation-context)
                                              (:library-prototypes old-state []))
                        :project-prototypes (if (or (= :all kind) (= :project kind))
                                              (g/node-value extensions :project-prototypes evaluation-context)
                                              (:project-prototypes old-state [])))
                      evaluation-context)]
      (g/user-data-swap! extensions :state (constantly new-state))
      (reload-language-servers! project new-state evaluation-context)
      (reload-commands! project new-state evaluation-context)
      nil)))

(defn- hook-exception->error [^Throwable ex project hook-keyword]
  (let [^Throwable root (stacktrace/root-cause ex)
        message (ex-message root)
        [_ file line :as match] (re-find console/line-sub-regions-pattern message)]
    (g/map->error
      (cond-> {:_node-id (or (when match (project/get-resource-node project file))
                             (project/get-resource-node project hooks-file-path))
               :message (str (name hook-keyword) " in " hooks-file-path " failed: " message)
               :severity :fatal}

              line
              (assoc-in [:user-data :cursor-range]
                        (data/line-number->CursorRange (Integer/parseInt line)))))))

(defn execute-hook!
  "Execute hook defined in this project

  Returns a CompletableFuture that will finish when the hook processing is
  finished. If the hook execution fails, the error will be reported to the user
  and the future will be completed as per exception policy.

  Args:
    project         the project node id
    hook-keyword    keyword like :on_build_started
    opts            an object that will be serialized and passed to the Lua
                    hook function. WARNING: all node ids should be wrapped with
                    vm/wrap-user-data since Lua numbers lack necessary precision

  Optional kv-args:
    :exception-policy    what to do if the hook eventually fails, can be either:
                           :as-error    transform exception to error value
                                        suitable for the graph
                           :ignore      return nil
                         When not provided, the exception will be re-thrown"
  [project hook-keyword opts & {:keys [exception-policy]}]
  (g/with-auto-evaluation-context evaluation-context
    (let [{:keys [rt display-output! hooks] :as state} (ext-state project evaluation-context)]
      (if-let [lua-fn (get hooks hook-keyword)]
        (-> (rt/invoke-suspending rt lua-fn (rt/->lua opts))
            (future/then
              (fn [lua-result]
                (when-let [actions (rt/->clj rt lua-result)]
                  (lsp.async/with-auto-evaluation-context evaluation-context
                    (actions/perform! actions project state evaluation-context)))))
            (future/catch
              (fn [ex]
                (error-handling/display-script-error! display-output! (str "hook " (name hook-keyword)) ex)
                (case exception-policy
                  :as-error (hook-exception->error ex project hook-keyword)
                  :ignore nil
                  (throw ex)))))
        (future/completed nil)))))

;; endregion
