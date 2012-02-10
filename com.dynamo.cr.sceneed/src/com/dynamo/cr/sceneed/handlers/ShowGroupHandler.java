package com.dynamo.cr.sceneed.handlers;

import java.util.Map;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.menus.UIElement;

import com.dynamo.cr.sceneed.Activator;

public class ShowGroupHandler extends AbstractHandler implements IElementUpdater {

    public static final String PREFERENCE_PATTERN = "groupHidden_%s";
    public static final String COMMAND_ID = "com.dynamo.cr.sceneed.commands.showGroup";
    public static final String GROUP_PARAMETER_ID = "com.dynamo.cr.sceneed.commandParameters.group";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        String group = event.getParameter(GROUP_PARAMETER_ID);
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        String prefs_name = String.format(PREFERENCE_PATTERN, group);
        boolean enabled = store.getBoolean(prefs_name);
        store.setValue(prefs_name, !enabled);
        return null;
    }

    @Override
    public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters) {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        String group = (String)parameters.get(GROUP_PARAMETER_ID);
        boolean enabled = store.getBoolean(String.format(PREFERENCE_PATTERN, group));
        element.setChecked(!enabled);
    }

}
