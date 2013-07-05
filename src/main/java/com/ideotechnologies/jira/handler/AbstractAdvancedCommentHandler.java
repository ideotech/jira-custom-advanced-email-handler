package com.ideotechnologies.jira.handler;

import com.atlassian.core.util.map.EasyMap;
import com.atlassian.crowd.embedded.api.User;
import com.atlassian.jira.JiraApplicationContext;
import com.atlassian.jira.config.properties.ApplicationProperties;
import com.atlassian.jira.event.issue.IssueEventSource;
import com.atlassian.jira.event.type.EventType;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueFactory;
import com.atlassian.jira.issue.comments.Comment;
import com.atlassian.jira.issue.comments.CommentManager;
import com.atlassian.jira.issue.util.IssueUpdateBean;
import com.atlassian.jira.issue.util.IssueUpdater;
import com.atlassian.jira.mail.MailThreadManager;
import com.atlassian.jira.plugin.assignee.AssigneeResolver;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.security.Permissions;
import com.atlassian.jira.service.util.ServiceUtils;
import com.atlassian.jira.web.FieldVisibilityManager;
import org.apache.log4j.Logger;

import javax.mail.Message;
import javax.mail.MessagingException;
import java.io.IOException;
import java.util.Collection;

/**
 * Created with IntelliJ IDEA.
 * User: s.genin
 * Date: 06/04/13
 * Time: 17:33
 * To change this template use File | Settings | File Templates.
 */
public abstract class AbstractAdvancedCommentHandler extends AbstractAdvancedEmailHandler {

    private static final Logger log = Logger.getLogger(AbstractAdvancedCommentHandler.class);
    private final PermissionManager permissionManager;
    private final IssueUpdater issueUpdater;
    Boolean registerSenderInCommentText=false;

    AbstractAdvancedCommentHandler(PermissionManager permissionManager, IssueUpdater issueUpdater, CommentManager commentManager, IssueFactory issueFactory, ApplicationProperties applicationProperties, JiraApplicationContext jiraApplicationContext, final AssigneeResolver assigneeResolver, final FieldVisibilityManager fieldVisibilityManager)
    {
        super(commentManager, issueFactory, applicationProperties, jiraApplicationContext,assigneeResolver,fieldVisibilityManager, issueUpdater);
        this.permissionManager = permissionManager;
        this.issueUpdater = issueUpdater;
    }


    public boolean handleMessage(Message message,com.atlassian.jira.service.util.handler.MessageHandlerContext context) throws MessagingException
    {
        if (!canHandleMessage(message))
        {
            return false;
        }

        try
        {
            String subject = message.getSubject();
            Issue issue = ServiceUtils.findIssueObjectInString(subject);

            if (issue == null)
            {
                // If we cannot find the issue from the subject of the e-mail message
                // try finding the issue using the in-reply-to message id of the e-mail message
                issue = getAssociatedIssue(message);
            }

            //if the subject line contains a valid project
            if (issue != null)
            {
                String body = getEmailBody(message,registerSenderInCommentText);

                if (body != null)
                {
                    body+=getAttachmentNamesIfNecessary(message);
                    //get either the sender of the message, or the default reporter
                    User reporter = getReporter(message);

                    //no reporter - so reject the message
                    if (reporter == null)
                    {
                        log.warn("The mail 'FROM' does not match a valid user");
                        log.warn("This user is not in jira so can not add a comment: " + message.getFrom()[0]);
                        return false; // Don't delete an email if we don't deal with it.
                    }

                    try
                    {
                        if (!permissionManager.hasPermission(Permissions.COMMENT_ISSUE, issue, reporter))
                        {
                            log.warn(reporter.getDisplayName() + " does not have permission to comment on an issue in project: " + issue.getKey());
                            return false;
                        }

                        Comment comment = commentManager.create(issue, reporter.getName(), body, null, null, false);

                        // Record the message id of this e-mail message so we can track replies to this message
                        // and associate them with this issue
                        recordMessageId(MailThreadManager.MailAction.ISSUE_COMMENTED_FROM_EMAIL, message, issue);

                        // Create attachments, if there are any attached to the message
                        Collection attachmentsChangeItems = null;
                        try
                        {
                            attachmentsChangeItems = createAttachmentsForMessage(message, issue);
                        }
                        catch (IOException e)
                        {
                            // failed to create attachment, but we still want to delete message
                            // no log required as the exception is already logged
                        }
                        catch (MessagingException e)
                        {
                            // failed to create attachment, but we still want to delete message
                            // no log required as the exception is already logged
                        }

                        update(attachmentsChangeItems, issue, reporter, comment);

                        return true; //delete message as we've left a comment now
                    }
                    catch (Exception e)
                    {
                        log.warn("Exception creating comment " + e.getMessage(), e);
                    }
                }
            }
            else
            {
                log.warn("MessagingException no corresponding issue");
            }
        }
        catch (Exception e)
        {
            log.warn("MessagingException creating comment " + e.getMessage(), e);
        }
        return false; // Don't delete message
    }

    private void update(Collection attachmentsChangeItems, Issue issue, User reporter, Comment comment) {
        // Get the eventTypeId to dispatch
        Long eventTypeId = getEventTypeId(attachmentsChangeItems);

        // Need to update the Updated Date of an issue and dispatch an event
        IssueUpdateBean issueUpdateBean = new IssueUpdateBean(issue, issue, eventTypeId, reporter);
        // Set the comment on is issueUpdateBean such that the dispatched event will have access to it.
        // The comment is also needed for generating notification e-mails
        issueUpdateBean.setComment(comment);
        if (attachmentsChangeItems != null && !attachmentsChangeItems.isEmpty())
        {
            // If there were attachments added, add their change items to the issueUpdateBean
            issueUpdateBean.setChangeItems(attachmentsChangeItems);
        }

        issueUpdateBean.setDispatchEvent(true);
        issueUpdateBean.setParams(EasyMap.build("eventsource", IssueEventSource.ACTION));
        // Do not let the issueUpdater generate change items. We have already generated all the needed ones.
        // So pass in 'false'.
        issueUpdater.doUpdate(issueUpdateBean, false);

        //ComponentAccessor.getIssueEventManager().dispatchEvent(EventType.ISSUE_COMMENTED_ID,issue,reporter,issueUpdateBean.getComment(),
        //      issueUpdateBean.getWorklog(),null,issueUpdateBean.getParams(),true);

    }

    /**
     * If there are attachments added dispatch {@link EventType#ISSUE_UPDATED_ID}, otherwise
     * dispatch {@link EventType#ISSUE_COMMENTED_ID}.
     */
    private static Long getEventTypeId(Collection attachmentsChangeItems)
    {
        // If we are only adding a comment then dispatch the ISSUE COMMENTED event
        Long eventTypeId = EventType.ISSUE_COMMENTED_ID;
        if (attachmentsChangeItems != null && !attachmentsChangeItems.isEmpty())
        {
            // If we are also adding attachments then dispatch the ISSUE UPDATED event instead
            eventTypeId = EventType.ISSUE_UPDATED_ID;
        }
        return eventTypeId;
    }


    protected boolean attachHtmlParts(final javax.mail.Part part) throws MessagingException, IOException
    {
        return false;
    }

}
