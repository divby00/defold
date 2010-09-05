#ifndef __GAMEOBJECTSCRIPT_H__
#define __GAMEOBJECTSCRIPT_H__

#include <dlib/array.h>

#include <resource/resource.h>
#include "gameobject.h"

/**
 * Private header for GameObject
 */

namespace dmGameObject
{
    struct Instance;
    struct UpdateContext;
    typedef Instance* HInstance;

    struct Script
    {
        int m_FunctionsReference;
    };
    typedef Script* HScript;

    struct ScriptInstance
    {
        HScript   m_Script;
        Instance* m_Instance;
        int       m_InstanceReference;
        int       m_ScriptDataReference;
    };

    struct ScriptWorld
    {
        ScriptWorld();

        dmArray<ScriptInstance*> m_Instances;
    };

    void    InitializeScript();
    void    FinalizeScript();

    HScript NewScript(const void* buffer, uint32_t buffer_size, const char* filename);
    bool    ReloadScript(HScript script, const void* buffer, uint32_t buffer_size, const char* filename);
    void    DeleteScript(HScript script);

    HScriptInstance NewScriptInstance(HScript script, HInstance instance);
    void            DeleteScriptInstance(HScriptInstance script_instance);

    bool    RunScript(HCollection collection, HScript script, const char* function_name, HScriptInstance script_instance, const UpdateContext* update_context);

    dmResource::CreateResult ResCreateScript(dmResource::HFactory factory,
                                             void* context,
                                             const void* buffer, uint32_t buffer_size,
                                             dmResource::SResourceDescriptor* resource,
                                             const char* filename);
    dmResource::CreateResult ResDestroyScript(dmResource::HFactory factory,
                                             void* context,
                                             dmResource::SResourceDescriptor* resource);
    dmResource::CreateResult ResRecreateScript(dmResource::HFactory factory,
                                               void* context,
                                               const void* buffer, uint32_t buffer_size,
                                               dmResource::SResourceDescriptor* resource,
                                               const char* filename);
    CreateResult ScriptNewWorld(void* context, void** world);
    CreateResult ScriptDeleteWorld(void* context, void* world);
    CreateResult ScriptCreateComponent(HCollection collection,
            HInstance instance,
            void* resource,
            void* world,
            void* context,
            uintptr_t* user_data);
    CreateResult ScriptInitComponent(HCollection collection,
            HInstance instance,
            void* context,
            uintptr_t* user_data);
    CreateResult ScriptDestroyComponent(HCollection collection,
            HInstance instance,
            void* world,
            void* context,
            uintptr_t* user_data);
    UpdateResult ScriptUpdateComponent(HCollection collection,
            const UpdateContext* update_context,
            void* world,
            void* context);
    UpdateResult ScriptOnEventComponent(HCollection collection,
            HInstance instance,
            const ScriptEventData* event_data,
            void* context,
            uintptr_t* user_data);
    InputResult ScriptOnInputComponent(HCollection collection,
            HInstance instance,
            const InputAction* input_action,
            void* context,
            uintptr_t* user_data);
}

#endif //__GAMEOBJECTSCRIPT_H__
