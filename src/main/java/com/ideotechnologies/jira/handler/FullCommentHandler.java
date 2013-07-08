/*
 * Copyright (c) 2002-2004
 * All rights reserved.
 */

package com.ideotechnologies.jira.handler;


import com.atlassian.jira.JiraApplicationContext;
import com.atlassian.jira.config.properties.ApplicationProperties;
import com.atlassian.jira.issue.IssueFactory;
import com.atlassian.jira.issue.comments.CommentManager;
import com.atlassian.jira.issue.util.IssueUpdater;
import com.atlassian.jira.plugin.assignee.AssigneeResolver;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.service.util.handler.MessageHandlerErrorCollector;
import com.atlassian.jira.web.FieldVisibilityManager;
import com.atlassian.mail.MailUtils;
import org.apache.log4j.Category;
import org.apache.log4j.Logger;

import javax.mail.MessagingException;
import javax.mail.Part;
import java.io.IOException;
import java.util.Map;

public class FullCommentHandler extends AbstractAdvancedCommentHandler
{
    private static final Category log = Logger.getLogger(FullCommentHandler.class);

    public FullCommentHandler(PermissionManager permissionManager, IssueUpdater issueUpdater, CommentManager commentManager, IssueFactory issueFactory, ApplicationProperties applicationProperties, JiraApplicationContext jiraApplicationContext,final AssigneeResolver assigneeResolver, final FieldVisibilityManager fieldVisibilityManager){
        super(permissionManager, issueUpdater, commentManager, issueFactory, applicationProperties, jiraApplicationContext,assigneeResolver,fieldVisibilityManager);
    }

    public void setRegisterSenderInCommentText(boolean registerSenderInCommentText) {
        this.registerSenderInCommentText = registerSenderInCommentText;
    }

    /**
     * Attaches plaintext parts.  
     * Plain text parts must be kept if they are not empty.
     *
     * @param part  the plain text part.
     * @return  true if the part is not empty, false otherwise
     */

    protected boolean attachPlainTextParts(final Part part) throws MessagingException, IOException
    {
        return !MailUtils.isContentEmpty(part);
    }


    @Override
    public void init(Map<String, String> stringStringMap, MessageHandlerErrorCollector messageHandlerErrorCollector) {

    }
}
