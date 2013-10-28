package com.ideotechnologies.jira.handler;

import com.atlassian.core.util.RandomGenerator;
import com.atlassian.crowd.embedded.api.Group;
import com.atlassian.crowd.embedded.api.User;
import com.atlassian.jira.JiraApplicationContext;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.config.properties.APKeys;
import com.atlassian.jira.config.properties.ApplicationProperties;
import com.atlassian.jira.event.user.UserEventType;
import com.atlassian.jira.exception.ParseException;
import com.atlassian.jira.issue.*;
import com.atlassian.jira.issue.comments.CommentManager;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutItem;
import com.atlassian.jira.issue.history.ChangeItemBean;
import com.atlassian.jira.issue.util.DefaultIssueChangeHolder;
import com.atlassian.jira.issue.util.IssueUpdater;
import com.atlassian.jira.mail.Email;
import com.atlassian.jira.mail.MailThreadManager;
import com.atlassian.jira.plugin.assignee.AssigneeResolver;
import com.atlassian.jira.service.util.handler.MessageHandler;
import com.atlassian.jira.service.util.handler.MessageHandlerContext;
import com.atlassian.jira.user.UserUtils;
import com.atlassian.jira.user.util.UserUtil;
import com.atlassian.jira.util.dbc.Assertions;
import com.atlassian.jira.web.FieldVisibilityManager;
import com.atlassian.jira.web.util.FileNameCharacterCheckerUtil;
import com.atlassian.mail.MailUtils;
import com.opensymphony.util.TextUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

abstract class AbstractAdvancedEmailHandler implements MessageHandler {

    final ApplicationProperties applicationProperties;
    final CommentManager commentManager;
    final IssueFactory issueFactory;
    final JiraApplicationContext jiraApplicationContext;
    final AssigneeResolver assigneeResolver;
    final FieldVisibilityManager fieldVisibilityManager;
    final IssueUpdater issueUpdater;
    private String fingerPrintPolicy;
    private String catchEmail;
    private String bulk;
    private boolean referenceAttachments;
    private boolean notifyUsers;
    String reporterUsername="";
    private boolean createUsers;
    private String userGroup="";
    private String ccField="";
    /** Regex for JIRA email address. */
    String jiraEmail;
    Boolean htmlFirst=false;
    Boolean forceProject=false;

    Map params = new HashMap();
    private static final FileNameCharacterCheckerUtil fileNameCharacterCheckerUtil = new FileNameCharacterCheckerUtil();

    private static final Logger log = Logger.getLogger(AdvancedCreateIssueHandler.class);

    AbstractAdvancedEmailHandler(final CommentManager commentManager, final IssueFactory issueFactory, final ApplicationProperties applicationProperties, final JiraApplicationContext jiraApplicationContext, final AssigneeResolver assigneeResolver, final FieldVisibilityManager fieldvisibilityManager, final IssueUpdater issueUpdater)
    {
        this.commentManager = commentManager;
        this.issueFactory = issueFactory;
        this.applicationProperties = applicationProperties;
        this.jiraApplicationContext = jiraApplicationContext;
        this.assigneeResolver = assigneeResolver;
        this.fieldVisibilityManager = fieldvisibilityManager;
        this.issueUpdater = issueUpdater;
    }

    void init(final Map params)
    {
        this.params=params;

        if (params.containsKey(Settings.KEY_FSPACK)) {
            createUsers=true;
            referenceAttachments=true;
            htmlFirst=true;
            forceProject=true;
        }

        if (params.containsKey(Settings.KEY_REPORTERUSERNAME))
        {
            reporterUsername = (String) params.get(Settings.KEY_REPORTERUSERNAME);
        }

        if (params.containsKey(Settings.KEY_CATCHEMAIL))
        {
            catchEmail = (String) params.get(Settings.KEY_CATCHEMAIL);
        }

        if (params.containsKey(Settings.KEY_BULK))
        {
            bulk = (String) params.get(Settings.KEY_BULK);
        }

        if (params.containsKey(Settings.KEY_REFERENCEATTACHMENTS))
        {
            referenceAttachments = Boolean.valueOf((String) params.get(Settings.KEY_REFERENCEATTACHMENTS));
        }

        if (params.containsKey(Settings.KEY_CREATEUSERS))
        {
            createUsers = Boolean.valueOf((String) params.get(Settings.KEY_CREATEUSERS));

            if (createUsers)
            {
                // Check that the default reporter is NOT configured
                // As if it is configured and creating users is set to true,
                // it is ambiguous whether to create a new user or use the default reporter
                final boolean extUserManagement = applicationProperties.getOption(APKeys.JIRA_OPTION_USER_EXTERNALMGT);
                if (reporterUsername != null)
                {
                    if (extUserManagement)
                    {
                        log.warn("Default Reporter Username set to '" + reporterUsername + "', " + Settings.KEY_CREATEUSERS + " is set to true and external user management is enabled.");
                        log.warn("Ignoring the " + Settings.KEY_CREATEUSERS + " flag. Using the default Reporter username '" + reporterUsername + "'.");
                    }
                    else
                    {
                        log.warn("Default Reporter Username set to '" + reporterUsername + "' and " + Settings.KEY_CREATEUSERS + " is set to true.");
                        log.warn("Ignoring the Default Reporter Username, users will be created if they do not exist.");
                    }
                }
                else if (extUserManagement)
                {
                    log.warn(Settings.KEY_CREATEUSERS + " is set to true, but external user management is enabled.  Users will NOT be created.");
                }

            }
            //JRA-13996: Don't use Boolean.getBoolean(String), it actually looks up to see if a system property of the passed name is
            // set to true.
            notifyUsers = params.containsKey(Settings.KEY_NOTIFYUSERS) || Boolean.parseBoolean((String) params.get(Settings.KEY_NOTIFYUSERS));
        }
        else
        {
            log.debug("Defaulting to not creating users");
            createUsers = false;
            log.debug("Defaulting to notifying users since user creation is not specified");
            notifyUsers = true;
        }

        if (params.containsKey(Settings.KEY_USERGROUP)) {
            this.userGroup = (String)params.get(Settings.KEY_USERGROUP);
            if (!createUsers) {
                log.debug(Settings.KEY_USERGROUP + " parameter is set, but " +  Settings.KEY_CREATEUSERS + " is set to FALSE");
            }
        }

        if (params.containsKey(Settings.KEY_HTMLFIRST)) {
            htmlFirst = Boolean.valueOf((String)params.get(Settings.KEY_HTMLFIRST));
        }

        if (params.containsKey(Settings.KEY_FORCEPROJECT)) {
            forceProject = Boolean.valueOf((String)params.get(Settings.KEY_FORCEPROJECT));
        }

        if (params.containsKey(Settings.KEY_FINGER_PRINT) && Settings.VALUES_FINGERPRINT.contains(params.get(Settings.KEY_FINGER_PRINT)))
        {
            fingerPrintPolicy = (String) params.get(Settings.KEY_FINGER_PRINT);
        }
        else
        {
            log.debug("Defaulting to fingerprint policy of 'forward'");
            fingerPrintPolicy = Settings.VALUE_FINGER_PRINT_FORWARD;
        }

        if (params.containsKey(Settings.KEY_CCFIELD)) {
            this.ccField = (String) params.get(Settings.KEY_CCFIELD);
        }

    }

    /**
     * Validation call to be made at the start of handleMessage().<br> It sets a global boolean deleteEmail, whether the
     * email should be deleted if it cannot be handled. ie. return deleteEmail if canHandleMessage() is false
     *
     * @param message message to check if it can be handled
     * @return whether the message should be handled
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    boolean canHandleMessage(final Message message)
    {
        /**
         * If the message fails a finder print check, then we don't want to handle it.
         */
        if (!fingerPrintCheck(message))
        {
            return false;
        }

        if (checkBulk(message))
        {
            return false;
        }

        // if the recipient is specified, check it is present in the message and reject if not
        if (catchEmail != null)
        {
            //JRA-16176: If  a message's recipients cannot be parsed, then we assume that the message is invalid. This
            // will leave the message in the mail box if there is no forward address, otherwise it will forward the
            // message to the forward address.

            final boolean forCatchAll;
            try
            {
                forCatchAll = MailUtils.hasRecipient(catchEmail, message);
            }
            catch (final MessagingException exception)
            {
                log.debug("Could not parse message recipients. Assuming message is bad.", exception);

                return false;
            }
            if (!forCatchAll)
            {
                //
                // JRA-15580 - We should NEVER delete the email if its not intended for this "catchEmail"
                //
                logCantHandleRecipients(message);
                return false;
            }

        }

        return true;
    }

    /**
     * Determines if the given message is acceptable for handling based on the presence of any JIRA fingerprint headers
     * and this {@link com.atlassian.jira.service.util.handler.MessageHandler}'s configuration.
     *
     * @param message the message to check.
     * @return false only if this handler should not handle the message because of a JIRA fingerprint.
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    boolean fingerPrintCheck(final Message message)
    {
        boolean fingerPrintClean = true; // until proven guilty
        final List fingerPrintHeaders = getFingerPrintHeader(message);
        final String instanceFingerPrint = jiraApplicationContext.getFingerPrint();
        if (!fingerPrintHeaders.isEmpty())
        {
            if (log.isDebugEnabled())
            {
                log.debug("JIRA fingerprints found on on incoming email message: ");
                for (final Object fingerPrintHeader : fingerPrintHeaders)
                {
                    log.debug("fingerprint: " + fingerPrintHeader);
                }
            }
            if (fingerPrintHeaders.contains(instanceFingerPrint))
            {
                log.warn("Received message carrying this JIRA instance fingerprint (" + instanceFingerPrint + ")");
                if (Settings.VALUE_FINGER_PRINT_ACCEPT.equalsIgnoreCase(fingerPrintPolicy))
                {
                    log.warn("Handler is configured to accept such messages. Beware of mail loops: JRA-12467");
                }
                else if (Settings.VALUE_FINGER_PRINT_FORWARD.equalsIgnoreCase(fingerPrintPolicy))
                {
                    log.debug("Forwarding fingerprinted email.");
                    fingerPrintClean = false;
                }
                else if (Settings.VALUE_FINGER_PRINT_IGNORE.equalsIgnoreCase(fingerPrintPolicy))
                {
                    log.info("Handler is configured to ignore this message.");
                    fingerPrintClean = false;
                }

            }
            else
            {
                log.info("Received message with another JIRA instance's fingerprint");
            }
        }
        return fingerPrintClean;
    }

    private boolean checkBulk(final Message message)
    {
        try
        {
            if ("bulk".equalsIgnoreCase(getPrecedenceHeader(message)) || isDeliveryStatus(message) || isAutoSubmitted(message))
            {
                //default action is to process the email for backwards compatibility
                if (bulk != null)
                {
                    if (Settings.VALUE_BULK_IGNORE.equalsIgnoreCase(bulk))
                    {
                        log.debug("Ignoring email with bulk delivery type");
                        return true;
                    }
                    else if (Settings.VALUE_BULK_FORWARD.equalsIgnoreCase(bulk))
                    {
                        log.debug("Forwarding email with bulk delivery type");
                        return true;
                    }
                    else if (Settings.VALUE_BULK_DELETE.equalsIgnoreCase(bulk))
                    {
                        log.debug("Deleting email with bulk delivery type");
                        return true;
                    }
                }
            }
            return false;
        }
        catch (final MessagingException mex)
        {
            if (log.isDebugEnabled())
            {
                log.debug("Error occurred while looking for bulk headers - assuming not bulk email: " + mex.getMessage(), mex);
            }
            return false;
        }
    }

    /**
     * This method just runs through all recipients of the email and builds up a debug string so that we can see who was
     * a recipient of the email.
     *
     * @param message the message that we can't handle.
     */
    private void logCantHandleRecipients(final Message message)
    {
        final Address[] addresses;
        try
        {
            addresses = message.getAllRecipients();
        }
        catch (final MessagingException e)
        {
            log.debug("Cannot handle message. Unable to parse recipient addresses.", e);
            return;
        }

        if ((addresses == null) || (addresses.length == 0))
        {
            log.debug("Cannot handle message.  No recipient addresses found.");
        }
        else
        {
            final StringBuilder recipients = new StringBuilder();

            for (int i = 0; i < addresses.length; i++)
            {
                final InternetAddress email = (InternetAddress) addresses[i];
                if (email != null)
                {
                    recipients.append(email.getAddress());
                    if ((i + 1) < addresses.length)
                    {
                        recipients.append(", ");
                    }
                }
            }
            log.debug("Cannot handle message as the recipient(s) (" + recipients.toString() + ") do not match the catch email " + catchEmail);
        }
    }

    /**
     * Returns the values of the JIRA fingerprint headers on the message, or null if there is no such header. Messages
     * sent by v3.13 of JIRA and later should all carry the fingerprint header with the value being the instance's
     * "unique" fingerprint.
     *
     * @param message the message to get the header from.
     * @return the possibly empty list of values of the JIRA fingerprint headers of the sending instance.
     * @since v3.13
     */
    List<String> getFingerPrintHeader(final Message message)
    {
        List<String> headers = Collections.emptyList();
        try
        {
            final String[] headerArray = message.getHeader(Email.HEADER_JIRA_FINGER_PRINT);
            if (headerArray != null)
            {
                headers = Arrays.asList(headerArray);
            }
        }
        catch (final MessagingException e)
        {
            log.error("Failed to get mail header " + Email.HEADER_JIRA_FINGER_PRINT);
        }
        return headers;
    }

    /**
     * Extract the 'Precedence' header value from the message
     */
    String getPrecedenceHeader(final Message message) throws MessagingException
    {
        final String[] precedenceHeaders = message.getHeader("Precedence");
        String precedenceHeader;

        if ((precedenceHeaders != null) && (precedenceHeaders.length > 0))
        {
            precedenceHeader = precedenceHeaders[0];

            if (!StringUtils.isBlank(precedenceHeader))
            {
                return precedenceHeader;
            }
        }
        return null;
    }


    boolean isDeliveryStatus(final Message message) throws MessagingException
    {
        final String contentType = message.getContentType();
        //noinspection SimplifiableIfStatement
        if ("multipart/report".equalsIgnoreCase(MailUtils.getContentType(contentType)))
        {
            return contentType.toLowerCase().contains("report-type=delivery-status");
        }
        else
        {
            return false;
        }
    }

    boolean isAutoSubmitted(final Message message) throws MessagingException
    {

        final String[] autoSub = message.getHeader("Auto-Submitted");
        if (autoSub != null)
        {
            for (final String auto : autoSub)
            {
                if (!"no".equalsIgnoreCase(auto))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get the reporter from the email address who sent the message, or else create a new  user if creating users is set
     * to true, or use the default reporter if one is specified.
     * <p/>
     * If neither of these are found, return null.
     *
     * @param message The email message to search through.
     * @return The user who sent the email, or the default reporter, or null.
     * @throws MessagingException If there is a problem getting the user who created the message.
     */
    User getReporter(final Message message) throws MessagingException
    {
        User reporter = getAuthorFromSender(message);

        if (reporter == null)
        {
            //if createUsers is set, attempt to create a new reporter from the e-mail details
            if (createUsers)
            {
                reporter = createUserForReporter(message);
            }

            // If there's a default reporter set, and we haven't created a reporter yet, attempt to use the
            //default reporter.
            if ((reporterUsername != null) && (reporter == null))
            {
                // Sender not registered with JIRA, use default reporter
                reporter = UserUtils.getUser(reporterUsername);
                log.warn("Default reporter '" + reporterUsername + "' not found.");
            }
        }
        return reporter;
    }

    /**
     * For each sender of the given message in turn, look up a User first with a case-insensitively equal email address,
     * and failing that, with a username equal to the email address.
     * <p/>
     * JIRA wants to do this because when we create users in email handlers, we set email and username equal. If a user
     * subsequently changes their email address, we must not assume they don't exist and create them with the email
     * address as the username.
     *
     * @param message the message from which to get the User.
     * @return the User matching the sender of the message or null if none found.
     * @throws MessagingException if there's strife getting the message sender.
     */
    User getAuthorFromSender(final Message message) throws MessagingException
    {

        final List<String> senders = MailUtils.getSenders(message);
        User user = null;
        for (final String emailAddress : senders)
        {
            user = findUserByEmail(emailAddress);
            if (user != null)
            {
                break;
            }
            user = findUserByUsername(emailAddress);
            if (user != null)
            {
                break;
            }
        }

        return user;
    }

    /**
     * Finds the user with the given username or returns null if there is no such User. Convenience method which doesn't
     * throw up.
     *
     * @param username the username.
     * @return the User or null.
     */
    User findUserByUsername(final String username)
    {
        return UserUtils.getUser(username);
    }

    /**
     * Returns the first User found with an email address that equals the given emailAddress case insensitively.
     *
     * @param emailAddress the email address to match.
     * @return the User.
     */
    User findUserByEmail(final String emailAddress)
    {
        for (final com.atlassian.crowd.embedded.api.User user : UserUtils.getAllUsers())
        {
            if (emailAddress.equalsIgnoreCase(user.getEmailAddress()))
            {
                return user;
            }
        }
        return null;
    }

    /**
     * Tries to create a user using the details provided by the reporter.  Fails if external user management is turned on
     * or, if no valid from email address was specified.
     *
     * @param message The original e-mail message.
     * @return A new user or null.
     */
    User createUserForReporter(final Message message)
    {
        User reporter = null;
        try
        {
            //If External User Management is set, throw an exception with the correct warning to be logged.
            if (applicationProperties.getOption(APKeys.JIRA_OPTION_USER_EXTERNALMGT))
            {
                log.warn("External user management is enabled. Contact your Administrator");
                return null;
            }

            // If reporter is not a recognised user, then create one from the information in the e-mail
            log.debug("Cannot find reporter for message. Creating new user.");

            final InternetAddress internetAddress = (InternetAddress) message.getFrom()[0];
            final String reporterEmail = internetAddress.getAddress();
            if (!TextUtils.verifyEmail(reporterEmail))
            {
                log.error("The email address [" + reporterEmail + "] received was not valid. Ensure that your mail client specified a valid 'From:' mail header. (see JRA-12203)");
                return null;
            }
            String fullName = internetAddress.getPersonal();
            if ((fullName == null) || (fullName.trim().length() == 0))
            {
                fullName = reporterEmail;
            }

            final String password = RandomGenerator.randomPassword();
            final UserUtil userUtil = ComponentAccessor.getUserUtil();

            if (notifyUsers)
            {
                reporter = userUtil.createUserWithNotification(reporterEmail, password, reporterEmail, fullName, UserEventType.USER_CREATED);
            }
            else
            {
                reporter = userUtil.createUserNoNotification(reporterEmail, password, reporterEmail, fullName);
            }
            log.debug("Created user " + reporterEmail + " as reporter of email-based issue.");

            if (userGroup != null) {
                // Remove all groups the user belong to

                SortedSet <String> groupNames=userUtil.getGroupNamesForUser(reporter.getName());

                List <Group> removeGroups=new ArrayList<Group>();

                for (String groupName : groupNames) {
                    removeGroups.add(ComponentAccessor.getGroupManager().getGroup(groupName));
                }

                userUtil.removeUserFromGroups(removeGroups,reporter);

                // Add the user to the parameter group
                userUtil.addUserToGroup(ComponentAccessor.getGroupManager().getGroup(userGroup),reporter);
            }

        }
        catch (final Exception e)
        {
            log.error("Error occurred while automatically creating a new user from email: ", e);
        }
        return reporter;
    }

    Issue getAssociatedIssue(final Message message)
    {
        // Test if the message has In-Reply-To header to a message that is associated with an issue
        return ComponentAccessor.getMailThreadManager().getAssociatedIssueObject(message);
    }

    void recordMessageId(MailThreadManager.MailAction type, Message message, Issue issue)
            throws MessagingException
    {
        String[] messageIds = message.getHeader("Message-Id");
        if ((messageIds != null) && (messageIds.length > 0))
        {
            Address[] fromAddresses = message.getFrom();
            String fromAddress = null;
            if ((fromAddresses != null) && (fromAddresses.length > 0))
            {
                fromAddress = ((InternetAddress)fromAddresses[0]).getAddress();
            }

            ComponentAccessor.getMailThreadManager().storeIncomingMessageId(messageIds[0],fromAddress,issue,type);
        }
    }
    Collection<ChangeItemBean> createAttachmentsForMessage(Message message, Issue issue)
            throws IOException, MessagingException
    {
        Collection<ChangeItemBean> attachmentChangeItems = new ArrayList<ChangeItemBean>();
//        if (this.applicationProperties.getOption("jira.option.allowAttachments"))
        if (ComponentAccessor.getAttachmentManager().attachmentsEnabled())
        {
            String disposition = message.getDisposition();
            if ((message.getContent() instanceof Multipart))
            {
                Multipart multipart = (Multipart)message.getContent();
                Collection<ChangeItemBean> changeItemBeans = handleMultipart(multipart, message, issue);
                if ((changeItemBeans != null) && (!changeItemBeans.isEmpty()))
                {
                    attachmentChangeItems.addAll(changeItemBeans);
                }

            }
            else if ("attachment".equalsIgnoreCase(disposition))
            {
                if (log.isDebugEnabled())
                {
                    log.debug("Trying to add attachment to issue from attachment only message.");
                }

                ChangeItemBean res = saveAttachmentIfNecessary(message, null, getReporter(message), issue);
                if (res != null)
                {
                    attachmentChangeItems.add(res);
                }

            }

        }
        else if (log.isDebugEnabled())
        {
            log.debug("Unable to add message attachments to issue: JIRA Attachments are disabled.");
        }
        return attachmentChangeItems;
    }

    ChangeItemBean saveAttachmentIfNecessary(Part part, Message containingMessage, User reporter, Issue issue)
            throws MessagingException, IOException
    {
        boolean keep = shouldAttach(part, containingMessage);
        if (keep)
        {
            return createAttachmentWithPart(part, reporter, issue);
        }
        return null;
    }

    final boolean shouldAttach(Part part, Message containingMessage)
            throws MessagingException, IOException
    {
        Assertions.notNull("part", part);

        if (log.isDebugEnabled())
        {
            log.debug("Checking if attachment should be added to issue:");
            log.debug("\tContent-Type: " + part.getContentType());
            log.debug("\tContent-Disposition: " + part.getDisposition());
        }

        boolean attach;
        if ((MailUtils.isPartMessageType(part)) && (null != containingMessage))
        {
            log.debug("Attachment detected as a rfc/822 message.");
            attach = attachMessagePart(part, containingMessage);
        }
        else
        {
            if (MailUtils.isPartAttachment(part))
            {
                log.debug("Attachment detected as an 'Attachment'.");
                attach = attachAttachmentsParts(part);
            }
            else
            {
                if (MailUtils.isPartInline(part))
                {
                    log.debug("Attachment detected as an inline element.");
                    attach = attachInlineParts(part);
                }
                else
                {
                    if (MailUtils.isPartPlainText(part))
                    {
                        log.debug("Attachment detected as plain text.");
                        attach = attachPlainTextParts(part);
                    }
                    else
                    {
                        if (MailUtils.isPartHtml(part))
                        {
                            log.debug("Attachment detected as HTML.");
                            attach = attachHtmlParts(part);
                        }
                        else
                        {
                            if (MailUtils.isPartRelated(containingMessage))
                            {
                                log.debug("Attachment detected as related content.");
                                attach = attachRelatedPart(part);
                            }
                            else
                            {
                                attach = false;
                            }
                        }
                    }
                }
            }
        }
        if (log.isDebugEnabled())
        {
            if (attach)
            {
                log.debug("Attachment was added to issue");
            }
            else
            {
                log.debug("Attachment was ignored.");
            }
        }

        return attach;
    }

    private Collection<ChangeItemBean> handleMultipart(Multipart multipart, Message message, Issue issue)
            throws MessagingException, IOException
    {
        Collection<ChangeItemBean> attachmentChangeItems = new ArrayList<ChangeItemBean>();
        int i = 0; for (int n = multipart.getCount(); i < n; i++)
    {
        if (log.isDebugEnabled())
        {
            log.debug(String.format("Adding attachments for multi-part message. Part %d of %d.", i + 1, n));
        }

        BodyPart part = multipart.getBodyPart(i);

        boolean isContentMultipart = part.getContent() instanceof Multipart;
        if (isContentMultipart)
        {
            attachmentChangeItems.addAll(handleMultipart((Multipart)part.getContent(), message, issue));
        }
        else
        {
            ChangeItemBean res = saveAttachmentIfNecessary(part, message, getReporter(message), issue);
            if (res != null)
            {
                attachmentChangeItems.add(res);
            }
        }
    }

        return attachmentChangeItems;
    }

    ChangeItemBean createAttachmentWithPart(Part part, User reporter, Issue issue)
            throws IOException
    {
        try
        {
            String contentType = MailUtils.getContentType(part);
            String rawFilename = part.getFileName();
            String filename = getFilenameForAttachment(part);

            File file = getFileFromPart(part, issue != null ? issue.getKey() : "null");

            if (log.isDebugEnabled())
            {
                log.debug("part=" + part);
                log.debug("Filename=" + filename + ", content type=" + contentType + ", content=" + part.getContent());
            }

            AttachmentManager attachmentManager = getAttachmentManager();
            filename = renameFileIfInvalid(filename, issue, reporter);
            ChangeItemBean cib = attachmentManager.createAttachment(file, filename, contentType, reporter, issue);
            if (cib != null)
            {
                if (log.isDebugEnabled())
                {
                    log.debug("Created attachment " + rawFilename + " for issue " + issue.getKey());
                }
                return cib;
            }

            log.debug("Encountered an error creating the attachment " + rawFilename + " for issue " + issue.getKey());
            return null;
        }
        catch (Exception e)
        {
            log.error("Exception while creating attachment for issue " + (issue != null ? issue.getKey() : "null") + ": " + e, e);
            throw new IOException(e.getMessage());
        }
    }

    boolean attachRelatedPart(Part part)
            throws IOException, MessagingException
    {
        return !MailUtils.isContentEmpty(part);
    }

    boolean attachMessagePart(Part messagePart, Message containingMessage)
            throws IOException, MessagingException
    {
        boolean keep = false;

        if (!shouldIgnoreEmailMessageAttachments())
        {
            if (!isReplyMessagePart(messagePart, containingMessage))
            {
                keep = !MailUtils.isContentEmpty(messagePart);

                if ((!keep) && (log.isDebugEnabled()))
                {
                    log.debug("Attachment not attached to issue: Message is empty.");
                }
            }
            else
            {
                log.debug("Attachment not attached to issue: Detected as reply.");
            }
        }
        else
        {
            log.debug("Attachment not attached to issue: Message attachment has been disabled.");
        }

        return keep;
    }

    boolean attachAttachmentsParts(Part part)
            throws MessagingException, IOException
    {
        return (!MailUtils.isContentEmpty(part)) && (!MailUtils.isPartSignaturePKCS7(part));
    }

    boolean attachInlineParts(Part part)
            throws MessagingException, IOException
    {
        return (!MailUtils.isContentEmpty(part)) && (!MailUtils.isPartSignaturePKCS7(part));
    }

    String getFilenameForAttachment(Part part)
            throws MessagingException, IOException
    {
        String filename = getFilenameFromPart(part);
        if (null == filename)
        {
            if (MailUtils.isPartMessageType(part))
            {
                filename = getFilenameFromMessageSubject(part);
            }
            else if (MailUtils.isPartInline(part))
            {
                filename = getFilenameFromContentType(part);
            }

        }

        if (null != filename)
        {
            if (StringUtils.isBlank(filename))
            {
                log.warn("Having found a filename(aka filename is not null) filename should not be an empty string, but is...");

                filename = null;
            }
        }

        return filename;
    }

    File getFileFromPart(Part part, String issueKey)
            throws IOException, MessagingException
    {
        File tempFile = null;
        try
        {
            tempFile = File.createTempFile("tempattach", "dat");
            FileOutputStream out = new FileOutputStream(tempFile);
            try
            {
                part.getDataHandler().writeTo(out);
            }
            finally
            {
                out.close();
            }
        }
        catch (IOException e)
        {
            log.error("Problem reading attachment from email for issue " + issueKey, e);
        }
        if (tempFile == null)
        {
            throw new IOException("Unable to create file?");
        }
        return tempFile;
    }

    AttachmentManager getAttachmentManager()
    {
        return ComponentAccessor.getAttachmentManager();
    }

    String renameFileIfInvalid(String filename, Issue issue, User reporter)
    {
        if (filename == null)
        {
            return null;
        }

        String replacedFilename = fileNameCharacterCheckerUtil.replaceInvalidChars(filename, Settings.INVALID_CHAR_REPLACEMENT);

        if (!filename.equals(replacedFilename))
        {
            if (log.isDebugEnabled())
            {
                log.debug("Filename was invalid: replacing '" + filename + "' with '" + replacedFilename + "'");
            }

            ComponentAccessor.getCommentManager().create(issue,reporter.getName(), ComponentAccessor.getJiraAuthenticationContext().getI18nHelper().getText("admin.renamed.file.cause.of.invalid.chars", filename, replacedFilename), false);

            return replacedFilename;
        }
        return filename;
    }

    private String getFilenameFromPart(Part part)
            throws MessagingException, IOException
    {
        String filename = part.getFileName();
        if (null != filename)
        {
            filename = MailUtils.fixMimeEncodedFilename(filename);
        }
        return filename;
    }

    private String getFilenameFromMessageSubject(Part part)
            throws MessagingException, IOException
    {
        Message message = (Message)part.getContent();
        String filename = message.getSubject();
        if (StringUtils.isBlank(filename))
        {
            try
            {
                filename = getMessageId(message);
            }
            catch (ParseException e)
            {
                filename = Settings.ATTACHED_MESSAGE_FILENAME;
            }
        }

        return filename;
    }

    private String getFilenameFromContentType(Part part)
            throws MessagingException
    {
        String filename = Settings.DEFAULT_BINARY_FILE_NAME;

        String contentType = MailUtils.getContentType(part);
        int slash = contentType.indexOf("/");
        if (-1 != slash)
        {
            String subMimeType = contentType.substring(slash + 1);

            if (!subMimeType.equals("bin"))
            {
                filename = contentType.substring(0, slash) + '.' + subMimeType;
            }
        }

        return filename;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    boolean shouldIgnoreEmailMessageAttachments()
    {
        return this.applicationProperties.getOption("jira.option.ignore.email.message.attachments");
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean isReplyMessagePart(Part messagePart, Message containingMessage)
            throws IOException, MessagingException
    {
        boolean replyMessage;
        try
        {
            replyMessage = isMessageInReplyToAnother(containingMessage, (Message)messagePart.getContent());
        }
        catch (ParseException e)
        {
            log.debug("Can't tell if the message is in reply to the attached message -- will attach it in case");
            replyMessage = false;
        }

        return replyMessage;
    }

    private boolean isMessageInReplyToAnother(Message containingMessage, Message attachedMessage)
            throws MessagingException, ParseException
    {
        String attachMessageId = getMessageId(attachedMessage);
        String[] replyToIds = containingMessage.getHeader(Settings.HEADER_IN_REPLY_TO);

        if (log.isDebugEnabled())
        {
            log.debug("Checking if attachment was reply to containing message:");
            log.debug("\tAttachment message id: " + attachMessageId);
            log.debug("\tNew message reply to values: " + Arrays.toString(replyToIds));
        }

        if (replyToIds != null)
        {
            for (String id : replyToIds)
            {
                if ((id != null) && (id.equalsIgnoreCase(attachMessageId)))
                {
                    return true;
                }
            }
        }
        return false;
    }

    String getMessageId(Message message)
            throws MessagingException, ParseException
    {
        String[] originalMessageIds = message.getHeader(Settings.HEADER_MESSAGE_ID);
        if ((originalMessageIds == null) || (originalMessageIds.length == 0))
        {
            String msg = "Could not retrieve Message-ID header from message: " + message;
            log.debug(msg);
            throw new ParseException(msg);
        }
        return originalMessageIds[0];
    }

    String getEmailBody(Message message, Boolean registerSenderInCommentText) throws MessagingException
    {
        String body ="";
        if (htmlFirst==true)
            body= HtmlMailUtils.getBody(message, true);
        else
            body=MailUtils.getBody(message);

        if (registerSenderInCommentText)
        {
            if ((message.getFrom() != null) && (message.getFrom().length > 0)) {
                body = "{panel:bgColor=yellow}*WARNING* - the issue REPORTER was not initially a known JIRA user - it was automatically set to a generic support account, please correct this as necessary.{panel}\n" + body;

                body = body + "[Commented via e-mail received from: " + message.getFrom()[0] + "]\n";
            }

        }

        return body;
    }


    Boolean addCCUserToCustomField(Message message,Issue issue) throws MessagingException {

        Boolean shallReindex=false;
        if (ccField != "") {

            MutableIssue mutableIssue = ComponentAccessor.getIssueManager().getIssueObject(issue.getKey());
            CustomField customFieldToSet=ComponentAccessor.getCustomFieldManager().getCustomFieldObject(ccField);
            // List<User> multiSelectValues = new ArrayList<User>();
            List <User> currentCCList = (List)mutableIssue.getCustomFieldValue(customFieldToSet);
            List <User> newCCList = new ArrayList<User>();

            if (currentCCList != null) {
                for (User ccItem : currentCCList){
                    newCCList.add(ccItem);
                }
            }

            //Address[] addresses = message.getRecipients(Message.RecipientType.CC);
            // Address[] addresses=new Address[0];

            //Address[] addresses=new Address[message.getRecipients(Message.RecipientType.CC).length+message.getRecipients(Message.RecipientType.TO).length];

            List<Address> addressList = new ArrayList<Address>();

            if (message.getRecipients(Message.RecipientType.TO)!= null){
               for (Address address : message.getRecipients(Message.RecipientType.TO)){
                  addressList.add(address);
               }
            }
            if (message.getRecipients(Message.RecipientType.CC)!= null)
               for (Address address : message.getRecipients(Message.RecipientType.CC)){
                   addressList.add(address);
               }
            if (message.getRecipients(Message.RecipientType.BCC)!= null)
                for (Address address : message.getRecipients(Message.RecipientType.BCC)){
                    addressList.add(address);
                }

            //System.arraycopy(message.getRecipients(Message.RecipientType.CC),0,addresses,0,message.getRecipients(Message.RecipientType.CC).length);
            //System.arraycopy(message.getRecipients(Message.RecipientType.TO),0,addresses,addresses.length,message.getRecipients(Message.RecipientType.TO).length);

            //Address[] addresses= (Address[]) addressList.toArray();
            //addressList.toArray(addresses);

            if (addressList != null) {
                for (Address address : addressList) {

                    if ((jiraEmail == null) || (cleanEmailAddress(address).compareTo(jiraEmail) != 0)) {
                    User user = UserUtils.getUserByEmail(cleanEmailAddress(address));
                    if (user == null) {
                        try {
                            user=createNewUser(address);
                        } catch (final Exception e) {
                            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                        }

                    }

                    if ((currentCCList == null) || (currentCCList != null && !currentCCList.contains(user)))
                        newCCList.add(user);

                }

                if (!newCCList.equals(currentCCList)) {
                    DefaultIssueChangeHolder issueChangeHolder = new DefaultIssueChangeHolder();
                    FieldLayoutItem fieldLayoutItem = ComponentAccessor.getFieldLayoutManager().getFieldLayout(issue).getFieldLayoutItem(
                            customFieldToSet);

                    customFieldToSet.updateValue(fieldLayoutItem, issue, new ModifiedValue(currentCCList, newCCList), issueChangeHolder);

                    if (mutableIssue.getKey() != null) {
                        // Remove duplicated issue update
                        if (mutableIssue.getModifiedFields().containsKey(customFieldToSet.getId())) {
                            mutableIssue.getModifiedFields().remove(customFieldToSet.getId());
                        }
                    }
                }

                shallReindex = true;
                }
            }

        }

        return shallReindex;
    }

    String cleanEmailAddress(Address address) {
        String cleanEmailAddress= new String();

        Pattern p = Pattern.compile("(.*?)<([^>]+)>.*,?", Pattern.DOTALL);
        Matcher m = p.matcher(address.toString());

        if (m.find())
            cleanEmailAddress=m.group(2);
        else
            cleanEmailAddress=address.toString();

        return cleanEmailAddress;
    }

    User createNewUser(Address address) throws Exception {

        final String password = RandomGenerator.randomPassword();
        final UserUtil userUtil = ComponentAccessor.getUserUtil();

        User user = userUtil.createUserNoNotification(cleanEmailAddress(address), password,
                cleanEmailAddress(address), cleanEmailAddress(address));

        // Remove all groups the user belong to
        SortedSet <String> groupNames=userUtil.getGroupNamesForUser(user.getName());

        List <Group> removeGroups=new ArrayList<Group>();

        for (String groupName : groupNames) {
            removeGroups.add(ComponentAccessor.getGroupManager().getGroup(groupName));
        }

        userUtil.removeUserFromGroups(removeGroups,user);

        // Add the user to the parameter group
        userUtil.addUserToGroup(ComponentAccessor.getGroupManager().getGroup(userGroup),user);

        return user;

    }

    public String getAttachmentNamesIfNecessary (Message message) throws Exception {

        String fileList=new String();

        String disposition = message.getDisposition();
        if ((message.getContent() instanceof Multipart))
        {
            Multipart multipart = (Multipart)message.getContent();
            fileList = getMultipartAttachmentsNames(multipart,message);
        }
        else if ("attachment".equalsIgnoreCase(disposition))
        {
            fileList+="\n\t[^";
            fileList+=getFilenameForAttachment(message);
            fileList+="]";
        }


        if (fileList.length() != 0) {
            return ("\\\\ \\\\{panel}These attachments relate to this message : "+fileList+"\n{panel}");
        }

        return "";
    }

    private String getMultipartAttachmentsNames(Multipart multipart, Message message)
            throws MessagingException, IOException
    {
        String fileList="";
        int i = 0;
        for (int n = multipart.getCount(); i < n; i++) {
        if (log.isDebugEnabled())
        {
            log.debug(String.format("Adding attachments for multi-part message. Part %d of %d.", i + 1, n));
        }

        BodyPart part = multipart.getBodyPart(i);

        boolean isContentMultipart = part.getContent() instanceof Multipart;
        if (isContentMultipart)
        {
            //fileList+="\n";
            fileList+=getMultipartAttachmentsNames((Multipart)part.getContent(),message);
        }
        //else if ("attachment".equalsIgnoreCase(part.getDisposition())||"inline".equalsIgnoreCase(part.getDisposition()))
        else if (shouldAttach(part,message) && (getFilenameForAttachment(part)!=null))
        {
            fileList+="\n    [^";
            fileList+=getFilenameForAttachment(part);
            fileList+="]";
        }
    }

        return fileList;
    }

    /**
                 * Perform the specific work of this handler for the given message.
                 *
                 * @return true if the message is to be deleted from the source.
                 * @throws MessagingException if anything went wrong.
                 */
    public abstract boolean handleMessage (Message message, MessageHandlerContext context) throws MessagingException;

    /**
     * This method determines whether or not plain text parts should be attached.
     *
     * @param part the part to be attached - already determined to be type text/plain.
     * @return true if the part should be attached; false otherwise
     * @throws java.io.IOException if java.mail complains
     * @throws javax.mail.MessagingException if java.mail complains
     */
    @SuppressWarnings("UnusedDeclaration")
    abstract protected boolean attachPlainTextParts(final Part part) throws MessagingException, IOException;

    /**
     * This method determines whether or not HTML parts should be attached.
     *
     * @param part the part to be attached - already determined to be type text/html.
     * @return true if the part should be attached; false otherwise
     * @throws java.io.IOException if java.mail complains
     * @throws javax.mail.MessagingException if java.mail complains
     */
    @SuppressWarnings("UnusedDeclaration")
    abstract protected boolean attachHtmlParts(final Part part) throws MessagingException, IOException;

}
