package com.ideotechnologies.jira.config;

import com.atlassian.configurable.ObjectConfigurationException;
import com.atlassian.jira.plugins.mail.webwork.AbstractEditHandlerDetailsWebAction;
import com.atlassian.jira.service.JiraServiceContainer;
import com.atlassian.jira.service.util.ServiceUtils;
import com.atlassian.jira.util.collect.MapBuilder;
import com.atlassian.plugin.PluginAccessor;
import com.ideotechnologies.jira.handler.Settings;

import java.util.Map;

@SuppressWarnings("UnusedDeclaration")
public class EditAdvancedCreateHandlerDetailsWebAction extends AbstractEditHandlerDetailsWebAction
{
    private String issueType;
    private String component;
    private String reporter;
    private String projectKey;
    private String ccAssignee;
    private String createUsers;
    private String notifyUsers;

    public EditAdvancedCreateHandlerDetailsWebAction(PluginAccessor pluginAccessor)
    {
        super(pluginAccessor);
    }

    public String getProjectKey() {
        return this.projectKey;
    }

    public void setProjectKey(String projectKey) {
        this.projectKey = projectKey;
    }

    public String getIssueType() {
        return this.issueType;
    }

    public void setIssueType(String issueType) {
        this.issueType = issueType;
    }

    public String getCcAssignee() {
        return this.ccAssignee;
    }

    public void setCcAssignee(String ccAssignee) {
        this.ccAssignee = ccAssignee;
    }

    public String getComponent() {
        return this.component;
    }

    public void setComponent(String component) {
        this.component = component;
    }

    public String getReporter() {
        return this.reporter;
    }

    public void setReporter(String reporter) {
        this.reporter = reporter;
    }

    public String getCreateUsers() {
        return this.createUsers;
    }

    public void setCreateUsers(String createUsers) {
        this.createUsers = createUsers;
    }

    public String getNotifyUsers() {
        return this.notifyUsers;
    }

    public void setNotifyUsers(String notifyUsers) {
        this.notifyUsers = notifyUsers;
    }

    protected void copyServiceSettings(JiraServiceContainer jiraServiceContainer) throws ObjectConfigurationException
    {
        String params = jiraServiceContainer.getProperty("handler.params");
        Map parameterMap = ServiceUtils.getParameterMap(params);
        this.issueType = ((String)parameterMap.get(Settings.KEY_ISSUETYPE));
        this.component = ((String)parameterMap.get(Settings.KEY_COMPONENT));
        this.reporter = ((String)parameterMap.get(Settings.KEY_REPORTERUSERNAME));
        this.projectKey = ((String)parameterMap.get(Settings.KEY_PROJECT));
        this.ccAssignee = ((String)parameterMap.get(Settings.CC_ASSIGNEE));
        this.createUsers = ((String)parameterMap.get(Settings.KEY_CREATEUSERS));
        this.notifyUsers = ((String)parameterMap.get(Settings.KEY_NOTIFYUSERS));
    }

    protected Map<String, String> getHandlerParams()
    {
        MapBuilder<String,String> mapBuilder = MapBuilder.newBuilder();
        mapBuilder.add(Settings.KEY_ISSUETYPE, this.issueType);
        mapBuilder.add(Settings.KEY_COMPONENT, this.component);
        mapBuilder.add(Settings.KEY_REPORTERUSERNAME, this.reporter);
        mapBuilder.add(Settings.KEY_PROJECT, this.projectKey);
        mapBuilder.add(Settings.CC_ASSIGNEE, this.ccAssignee);
        mapBuilder.add(Settings.KEY_CREATEUSERS, this.createUsers);
        mapBuilder.add(Settings.KEY_NOTIFYUSERS, this.notifyUsers);
        return mapBuilder.toMap();
    }

    protected void doValidation()
    {
        if (this.configuration == null) {
            return;
        }
        super.doValidation();
    }
}