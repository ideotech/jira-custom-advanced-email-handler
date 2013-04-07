package com.ideotechnologies.jira.config;

import com.atlassian.configurable.ObjectConfigurationException;
import com.atlassian.jira.plugins.mail.webwork.AbstractEditHandlerDetailsWebAction;
import com.atlassian.jira.service.JiraServiceContainer;
import com.atlassian.plugin.PluginAccessor;
import java.util.Map;

public class EditAdvancedCreateOrCommentHandlerDetailsWebAction extends AbstractEditHandlerDetailsWebAction
{
    public EditAdvancedCreateOrCommentHandlerDetailsWebAction(PluginAccessor pluginAccessor)
    {
        super(pluginAccessor);
    }

    protected void copyServiceSettings(JiraServiceContainer jiraServiceContainer)
            throws ObjectConfigurationException
    {
    }

    protected Map<String, String> getHandlerParams()
    {
        return null;
    }
}