package com.ideotechnologies.jira.handler;

import com.atlassian.core.util.ClassLoaderUtils;
import com.atlassian.crowd.embedded.api.User;
import com.atlassian.jira.ComponentManager;
import com.atlassian.jira.JiraApplicationContext;
import com.atlassian.jira.bc.issue.IssueService;
import com.atlassian.jira.bc.issue.IssueService.IssueResult;
import com.atlassian.jira.bc.issue.IssueService.UpdateValidationResult;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.config.ConstantsManager;
import com.atlassian.jira.config.properties.ApplicationProperties;
import com.atlassian.jira.issue.*;
import com.atlassian.jira.issue.comments.CommentManager;
import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.customfields.option.Options;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.Field;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutItem;
import com.atlassian.jira.issue.index.IndexException;
import com.atlassian.jira.issue.index.IssueIndexManager;
import com.atlassian.jira.issue.resolution.Resolution;
import com.atlassian.jira.issue.util.DefaultIssueChangeHolder;
import com.atlassian.jira.issue.util.IssueUpdater;
import com.atlassian.jira.plugin.assignee.AssigneeResolver;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.service.util.ServiceUtils;
import com.atlassian.jira.service.util.handler.MessageHandlerContext;
import com.atlassian.jira.service.util.handler.MessageHandlerErrorCollector;
import com.atlassian.jira.user.UserUtils;
import com.atlassian.jira.util.ErrorCollection;
import com.atlassian.jira.util.ImportUtils;
import com.atlassian.jira.util.JiraUtils;
import com.atlassian.jira.web.FieldVisibilityManager;
import com.atlassian.jira.workflow.*;
import com.atlassian.mail.MailUtils;
import com.opensymphony.workflow.loader.ActionDescriptor;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.Predicate;
import org.apache.log4j.Logger;

import javax.mail.Address;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Part;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This is the main handler class of the JIRA Advanced Mail Handler.
 * <p>
 * It permits to create a new issue, or add a comment to an existing issue, from
 * an incoming message. If the recipient or subject contains a project key the
 * message is added as a comment to that issue; in this case, many of the issue
 * options can be specified directly in the email body. If no project key is
 * found, a new issue is created in the specified project.
 *
 * @author Brice Copy on the basis of DAniele Raffo's work and the
 *         CreateOrCommentHandler class, copyright (c) 2002-2006 Atlassian
 */
@SuppressWarnings("BooleanMethodIsAlwaysInverted")
public class AdvancedCreateOrCommentHandler extends AbstractAdvancedEmailHandler {

    //    private final ApplicationProperties applicationProperties;
    @SuppressWarnings("UnusedDeclaration")

    private MessageHandlerErrorCollector monitor;
    @SuppressWarnings("UnusedDeclaration")
    private String catchEmail;
//    private final JiraApplicationContext jiraApplicationContext;
    @SuppressWarnings("UnusedDeclaration")
    private String fingerPrintPolicy;


    private final Logger log = Logger.getLogger(AdvancedCreateOrCommentHandler.class);

    /** Default project where new issues are created. */
    private String defaultProjectKey;
    /** Default type for new issues. */
    private String defaultIssueType;
    /** If set (to anything), quoted text is removed from comments. */
    private Boolean stripQuotes;
    /** Regex for alias of JIRA email address. */
    private String jiraEmailAlias;
    /** related custom field */
    private String customField;

    private Collection<String> messages;

    /**
     * Collection of patterns defining messages scraped from subject to append
     * to issue summary
     */
    private final Map<String, SubjectRegexpReplace> subjectRegexps = new HashMap<String, SubjectRegexpReplace>();

    private final List<String> whiteListEntries = new ArrayList<String>();

    private AdvancedCreateOrCommentHandler(CommentManager commentManager, IssueFactory issueFactory, ApplicationProperties applicationProperties, JiraApplicationContext jiraApplicationContext, AssigneeResolver assigneeResolver, FieldVisibilityManager fieldVisibilityManager, IssueUpdater issueUpdater) {
        super(commentManager, issueFactory, applicationProperties, jiraApplicationContext, assigneeResolver, fieldVisibilityManager, issueUpdater);
    }

    @SuppressWarnings("ConstantConditions")
    public void init(Map<String, String> params,MessageHandlerErrorCollector messageHandlerErrorCollector)
    {

        super.init(params);

        if (params.containsKey(Settings.KEY_FSPACK))  {
            stripQuotes=true;
        }

        if (params.containsKey(Settings.KEY_PROJECT)) {
            this.defaultProjectKey = params.get(Settings.KEY_PROJECT);
        }

        if (params.containsKey(Settings.KEY_ISSUETYPE)) {
            this.defaultIssueType = params.get(Settings.KEY_ISSUETYPE);
        }

        if (params.containsKey(Settings.KEY_QUOTES)) {
            this.stripQuotes = Boolean.valueOf(params.get(Settings.KEY_QUOTES));
        }

        if (params.containsKey(Settings.KEY_JIRAEMAIL)) {
            jiraEmail = params.get(Settings.KEY_JIRAEMAIL);
        }

        if (params.containsKey(Settings.KEY_CUSTOMFIELD)) {
            this.customField = params.get(Settings.KEY_CUSTOMFIELD);
        }

        if (params.containsKey(Settings.KEY_JIRAALIAS))
            this.jiraEmailAlias = params.get(Settings.KEY_JIRAALIAS);
        else {
            this.jiraEmailAlias = this.jiraEmail;
        }

        for (Object key : params.keySet()) {
            if (((String)key).toLowerCase().trim().startsWith(Settings.KEY_WHITELIST)) {
                String whiteListExp = params.get(key);
                this.whiteListEntries.add(whiteListExp.trim());
                this.log.debug("Adding whiteList expression : '" + whiteListExp + "'");
            }

            if (((String)key).toLowerCase().trim().startsWith(Settings.KEY_SUBJECTREGEXP))
            {
                String subjectRegexp = params.get(key);
                this.log.debug("Found subject regexp pattern : '" + subjectRegexp + "'");

                Pattern pattern = Pattern.compile(Settings.KEY_SUBJECTREGEXP + "(.*)");
                Matcher matcher = pattern.matcher(key.toString());
                if (matcher.find()) {
                    String keyKey = matcher.group(1);
                    if (this.subjectRegexps.containsKey(keyKey))
                        this.subjectRegexps.get(keyKey).setRegexp(subjectRegexp);
                    else
                        this.subjectRegexps.put(keyKey, new SubjectRegexpReplace(subjectRegexp));
                }
                else
                {
                    this.log.warn("Malformed key for subject regexp pattern: " + key);
                }
            }
            if (((String)key).toLowerCase().trim().startsWith(Settings.KEY_SUBJECTREPLACE))
            {
                String subjectRegexp = params.get(key);
                this.log.debug("Found subject regexp replace pattern : '" + subjectRegexp + "'");

                Pattern pattern = Pattern.compile(Settings.KEY_SUBJECTREPLACE + "(.*)");
                Matcher matcher = pattern.matcher(key.toString());
                if (matcher.find()) {
                    String keyKey = matcher.group(1);
                    if (this.subjectRegexps.containsKey(keyKey)) {
                        this.subjectRegexps.get(keyKey).setReplace(subjectRegexp);
                    } else {
                        SubjectRegexpReplace srr = new SubjectRegexpReplace();
                        srr.setReplace(subjectRegexp);
                        this.subjectRegexps.put(keyKey, srr);
                    }
                } else {
                    this.log.warn("Malformed key for subject replace pattern: " + key);
                }

            }

        }

        this.log.debug("Params: " + this.defaultProjectKey + " - " + this.defaultIssueType + " - " + this.stripQuotes + " - " + this.jiraEmail + " - " + this.jiraEmailAlias);
    }


    @Override
    public boolean handleMessage(Message message, MessageHandlerContext context) throws MessagingException {
        Boolean shallReindex=false;
        log.debug("AdvancedCreateOrCommentHandler3.handleMessage");

        if (!canHandleMessage(message)) {
            return false;
        }

        String subject = message.getSubject();
        Issue issue = ServiceUtils.findIssueObjectInString(subject);

        IssueDescriptor issueDescriptor = MessageParser.parse(message,
                new String[] { jiraEmail, jiraEmailAlias });

        if (issue == null) {
            // If we cannot find the issue from the subject of the e-mail
            // message
            // try finding the issue using the in-reply-to message id of the
            // e-mail message
            issue = ComponentAccessor.getMailThreadManager().getAssociatedIssueObject(message);
        }

        if (issue!=null && forceProject==true && !issue.getProjectObject().getKey().equals(defaultProjectKey))
            issue=null;

        // Store the FROM address for later (if there's no FROM, we discard the
        // message !)
        Address[] from = message.getFrom();
        if (from == null || from.length == 0) {
            // No FROM address !?! Have the message deleted, we simply ignore it
            // !
            log.warn("Message has no FROM address in its header ! ignoring message...");
            return true;
        }

        // Try and resolve the sender of the message as a valid JIRA user...
        // We do not use the "default reporter" for this purpose, we need to
        // identify
        // the actual sender of the message.
        User sender;

        String fromEmail = extractEmailAddressOnly(from[0].toString());

        sender = UserUtils.getUserByEmail(fromEmail);
        if (sender != null) {
            this.log.debug("Found user " + sender.getName() + " for email " + fromEmail);
        }
        else {
            this.log.info("Could not find a user for email '" + fromEmail);
        }


        // /////////
        // JMH-17 : If any whiteList expressions are defined and the sender is
        // unknown to JIRA,
        // we must check whether the sender email matches any of the white
        // listed domains
        log.debug("WhiteListed domains count: " + whiteListEntries.size());
        log.debug("Sender is null ? " + (sender == null));

        if (sender == null && whiteListEntries.size() > 0) {

            log.debug("Trying to find a match for " + fromEmail);
            /**
             * If the from address does not match any of the whiteListed
             * domains...
             */
            String matchingWhiteList = (String) CollectionUtils.find(
                    whiteListEntries, new RegexpWhiteListMatchPredicate(
                    fromEmail));
            if (matchingWhiteList == null) {
                // .. then we delete the message and interrupt the processing
                log.warn("Sender "
                        + fromEmail
                        + " did not match any of the whiteList regular expressions");

                // Not in the whiteList : Have the message deleted, we simply
                // ignore it !
                return true;
            } else {
                log.debug("Sender " + fromEmail
                        + " matched whiteList expression " + matchingWhiteList
                        + ", processing message...");
            }
        }
        // JMH-17
        // ////////

        // If we have found an associated issue, we're processing a comment made
        // to it
        if (issue != null) {

            // ...If we could not identify the sender of the message earlier
            // as a valid user, we dump the sender's email address directly in
            // the comment
            // so it does not get lost
            boolean registerSenderInCommentText = (sender == null);

            // append message to issue summary based on defined regex

            appendRegexToSummary(message, issue, sender);

            // add the message as a comment to the issue...
            Boolean doDelete = true;
            if ((stripQuotes == null) || Settings.FALSE.equalsIgnoreCase(String.valueOf(stripQuotes))) {
                FullCommentHandler fc = new FullCommentHandler(ComponentAccessor.getPermissionManager(),
                        issueUpdater,commentManager,issueFactory,applicationProperties,jiraApplicationContext,
                        assigneeResolver,fieldVisibilityManager);
                fc.init(params);
                fc.setRegisterSenderInCommentText(registerSenderInCommentText);
                doDelete = fc.handleMessage(message,context); // get message with quotes
            } else {
                NonQuotedCommentHandler nq = new NonQuotedCommentHandler(ComponentAccessor.getPermissionManager(),
                        issueUpdater,commentManager,issueFactory,applicationProperties,jiraApplicationContext,
                        assigneeResolver,fieldVisibilityManager);
                nq.init(params);
                nq.setRegisterSenderInCommentText(registerSenderInCommentText);
                doDelete = nq.handleMessage(message,context); // get message without
                // quotes
            }

            MutableIssue currentIssue = ComponentAccessor.getIssueManager().getIssueObject(issue.getKey());

            User issueReporter = currentIssue.getReporter();

            if (sender != null && sender.equals(issueReporter) && customField!=null) {

                Integer customFieldPos=customField.indexOf(":");
                String customFieldName=customField.substring(0,customFieldPos);
                customFieldName="customfield_"+customFieldName;
                CustomField customFieldToSet=ComponentAccessor.getCustomFieldManager().getCustomFieldObject(customFieldName);
                String customFieldValueString=customField.substring(customField.indexOf(":")+1);
                Option customFieldValue;

                if (customFieldValueString.equals("null")) {
                    customFieldValue=null;
                }
                else {
                    OptionsManager optionsManager = ComponentManager.getComponentInstanceOfType(OptionsManager.class);
                    FieldConfig fieldConfig =customFieldToSet.getRelevantConfig(currentIssue);
                    Options options = optionsManager.getOptions(fieldConfig);
                    customFieldValue=options.getOptionForValue(customFieldValueString,null);

                }

                currentIssue.setCustomFieldValue(customFieldToSet, customFieldValue);


                Map<String, ModifiedValue> modifiedFields = currentIssue.getModifiedFields();

                FieldLayoutItem fieldLayoutItem =
                        ComponentAccessor.getFieldLayoutManager().getFieldLayout(issue).getFieldLayoutItem(
                                customFieldToSet);

                DefaultIssueChangeHolder issueChangeHolder = new DefaultIssueChangeHolder();

                final ModifiedValue modifiedValue = modifiedFields.get(customFieldToSet.getId());

                customFieldToSet.updateValue(fieldLayoutItem, currentIssue, modifiedValue, issueChangeHolder);

                shallReindex=true;

            }

            // ///////
            // JMH-14
            // Progress the issue in the workflow if required
            if (issueDescriptor.getWorkflowTarget() != null
                    && (!"".equals(issueDescriptor.getWorkflowTarget()))) {
                 applyWorkflowTransition(currentIssue, sender,
                        issueDescriptor.getWorkflowTarget(),
                        issueDescriptor.getResolution());

            }
            // /////////////

            shallReindex |= addCCUserToCustomField(message,currentIssue);

            if (shallReindex == true)
                reindexIssue(currentIssue);

            return doDelete;
        } else {
            AdvancedCreateIssueHandler createIssueHandler = new AdvancedCreateIssueHandler(commentManager,issueFactory,applicationProperties,jiraApplicationContext,assigneeResolver,fieldVisibilityManager,issueUpdater);
            createIssueHandler.setJiraEmail(jiraEmail);
            createIssueHandler.setIssueDescriptor(issueDescriptor);
            createIssueHandler.init(params,monitor);
            return createIssueHandler.handleMessage(message,context);
        }
    }

    @Override
    /**
     * Attaches plaintext parts. Plain text parts must be kept if they are not
     * empty.
     *
     * @param part
     *            the plain text part being tested
     * @return true if content is not empty and must be attached
     */
    protected boolean attachPlainTextParts(final Part part)
            throws MessagingException, IOException {
        return !MailUtils.isContentEmpty(part);
    }

    public class RegexpWhiteListMatchPredicate implements Predicate {

        final String m_fromAddress;

        public RegexpWhiteListMatchPredicate(String fromAddress) {
            m_fromAddress = fromAddress;
        }

        public boolean evaluate(Object object) {
            String whiteListExpression = (String) object;

            log.debug("Matching " + m_fromAddress + " with "
                    + whiteListExpression);
            //noinspection RedundantIfStatement
            if (Pattern.matches(whiteListExpression, m_fromAddress)) {
                return true;
            }
            return false;
        }

    }
    /**
     * Looks in subject to find patterns defined as subjectRegexp parameter and
     * appends them to issue summary according to subjectReplace parameter.
     *
     * @param message
     *            Message
     * @param issue
     *            Commented issue
     * @param user
     *            User doing the edit (must have proper permissions)
     */

    private void appendRegexToSummary(Message message, Issue issue,
                                      User user) {
        String subject;
        IssueService issueService = ComponentAccessor.getIssueService();
        try {
            if (user == null) {
                user = UserUtils.getUser(reporterUsername);
            }
            subject = message.getSubject();
            for (String key : subjectRegexps.keySet()) {
                Matcher matcher = subjectRegexps.get(key).getMatcher(subject);
                while (matcher.find()) {
                    String toAppend = subjectRegexps.get(key)
                            .getOutput(matcher);
                    IssueService.IssueResult issueResult = issueService.getIssue(
                            user, issue.getKey());
                    MutableIssue mutableIssue = issueResult.getIssue();
                    if (mutableIssue.getSummary().contains(toAppend)) {
                        log.info("Subject already contains the output regex. Not appending! Subject: |"+subject+"| and toAppend=|"+toAppend+"|");
                    } else {
                        log.info("Appending: " + toAppend);
                        IssueInputParameters issueInputParameters = new IssueInputParametersImpl();
                        issueInputParameters.setSummary(mutableIssue
                                .getSummary() + " " + toAppend);
                        UpdateValidationResult updateValidationResult = issueService
                                .validateUpdate(user, mutableIssue.getId(),
                                        issueInputParameters);
                        if (updateValidationResult.isValid()) {
                            IssueResult updateResult = issueService.update(
                                    user, updateValidationResult);
                            if (!updateResult.isValid()) {
                                log.error("Could not append '" + toAppend
                                        + "' to issue " + mutableIssue.getKey());
                            } else {
                                log.info("Message '" + toAppend
                                        + "' appended to summary of issue "
                                        + mutableIssue.getKey());
                            }
                        } else {
                            log.error("Could not append '" + toAppend
                                    + "' to issue " + mutableIssue.getKey());
                            for (String errMsg : updateValidationResult
                                    .getErrorCollection().getErrorMessages()) {
                                log.error("Validation error : " + errMsg);
                            }
                        }
                    }
                }
            }
        } catch (MessagingException e) {
            log.error("Couldn't read the message subject: " + e.getMessage());
        }
    }

    /**
     * Given an email address of the form "Arthur Dent <arthur.Dent@earth.com>",
     * this function returns "arthur.dent@earth.com".
     *
     * @param address full email address
     * @return The email address, without any surrounding characters
     */
    private static String extractEmailAddressOnly(String address) {

        // This regular expression means : match any sequence of
        // * words characters (a-z, 0 to 9, underscore) or dot followed by...
        // * An AT sign (@)
        // * words characters (a-z, 0 to 9, underscore) or dot followed by...
        // * between two to four characters
        Pattern p = Pattern
                .compile("[\\w\\-\\.]+@[\\w\\-\\.]+\\.[a-zA-Z]{2,4}");
        Matcher m = p.matcher(address);

        if (m.find()) {
            return m.group().toLowerCase();
        }

        return null;
    }

    /**
     * Attaches HTML parts. Comments never wish to keep HTML parts that are not
     * attachments, as they extract the plain text part and use that as content.
     * This method therefore is hard wired to always return false.
     *
     * @param part
     *            the HTML part being processed
     * @return always false
     * @throws MessagingException
     * @throws IOException
     */
    protected boolean attachHtmlParts(final Part part) throws MessagingException, IOException
    {
        return !MailUtils.isContentEmpty(part) && MailUtils.isPartAttachment(part);
    }

    /**
     * Progress an issue in the workflow, on behalf of the given user. Note that
     * this function is quite simple, it does not support setting custom fields
     * on the issue prior to performing the transition - the only supported
     * field is the RESOLUTION field.
     *
     * @param issueToSetStatusOn related Issue
     * @param user user performing the action
     * @param workflowTargetName Transition to execute
     * @param resolutionValue Resolution to set
     */
    @SuppressWarnings("unchecked")
    void applyWorkflowTransition(MutableIssue issueToSetStatusOn,
                                 User user, String workflowTargetName, String resolutionValue) {
        log.debug("Issue: " + issueToSetStatusOn.toString());
        WorkflowManager mgr = ComponentAccessor.getWorkflowManager();
        JiraWorkflow wf = mgr.getWorkflow(issueToSetStatusOn);
        WorkflowTransitionUtil workflowTransitionUtil = JiraUtils
                .loadComponent(WorkflowTransitionUtilImpl.class);

        log.debug("FYI, Issue " + issueToSetStatusOn.getKey()
                + " is managed by the [" + wf.getName() + "] workflow");

        log.debug("Issue type is '"
                + (issueToSetStatusOn.getIssueTypeObject() != null ? issueToSetStatusOn
                .getIssueTypeObject().getName() : "NULL !!!") + "'");

        IssueWorkflowManager issueWorkflowManager = getIssueWorkflowManager(user);
        ActionDescriptor targetAction = null;
        Collection availableActions = issueWorkflowManager.getAvailableActions(issueToSetStatusOn);

        for (Object availableAction : availableActions) {
            ActionDescriptor aDescriptor = (ActionDescriptor) availableAction;
            this.log.debug("Checking available action [" + aDescriptor.getId() + ":" + aDescriptor.getName() + "]");

            if (aDescriptor.getName().equalsIgnoreCase(workflowTargetName)) {
                this.log.debug("Requested workflow step is valid for issue [" + issueToSetStatusOn.getKey() + "] state: [" + aDescriptor.getId() + ":" + aDescriptor.getName() + "]");

                targetAction = aDescriptor;
                break;
            }
        }

        if (targetAction == null) {
            log.error("Workflow Transition had a problem with the given workflow step, it was not in the list of available next steps for the given issues current state");
            return;
        }

        Map<String, Object> workflowTransitionParams = new HashMap<String, Object>();

        workflowTransitionUtil.setIssue(issueToSetStatusOn);
        workflowTransitionUtil.setAction(targetAction.getId());
        workflowTransitionUtil.setUsername(user.getName());

        // Force the issue type id (the workflow engine complains it is missing
        // otherwise )
        Field issueTypeField = ComponentAccessor.getFieldManager().getField(
                IssueFieldConstants.ISSUE_TYPE);
        workflowTransitionParams.put(issueTypeField.getId(), issueToSetStatusOn
                .getIssueTypeObject().getId());

        Field resolutionField = ComponentAccessor.getFieldManager().getField(
                IssueFieldConstants.RESOLUTION);
        if (resolutionField != null) {
            String resolutionFieldId = resolutionField.getId();
            if (resolutionValue == null || "".equals(resolutionValue.trim())) {
                resolutionValue = "Fixed";
                ConstantsManager cmr = ComponentAccessor.getConstantsManager();
                Collection<Resolution> c = cmr.getResolutionObjects();
                for (Resolution resolution : c) {

                    if (resolution.getNameTranslation().equalsIgnoreCase(
                            resolutionValue)) {
                        log.debug("Matched resolution : "
                                + resolution.getNameTranslation());
                        workflowTransitionParams.put(resolutionFieldId,
                                resolution.getId());
                        break;
                    }
                }
            }
        }

        workflowTransitionUtil.setParams(workflowTransitionParams);

        ErrorCollection ecValidate = workflowTransitionUtil.validate();
        if (ecValidate.hasAnyErrors()) {
            log.debug("Workflow transition incomplete, there were "
                    + ecValidate.getErrors().size()
                    + " validate workflow transition errors");
            Map<String, String> validateErrors = ecValidate.getErrors();

            //noinspection LoopStatementThatDoesntLoop
            for (String fieldId : validateErrors.keySet()) {
                String msg = validateErrors.get(fieldId);
                log.error("Workflow Transition (validate) had a problem with the workflow field ["
                        + fieldId
                        + "], value ["
                        + workflowTransitionParams.get(fieldId)
                        + "], message : " + msg);
                return;
            }
        }

        // Execute workflow
        ErrorCollection ecProgress = workflowTransitionUtil.progress();
        if (ecProgress.hasAnyErrors()) {
            log.debug("Workflow transition incomplete, "
                    + ecProgress.getErrors().size()
                    + " progress workflow transition errors");
            Map<String, String> progressErrors = ecProgress.getErrors();

            //noinspection LoopStatementThatDoesntLoop
            for (String fieldId : progressErrors.keySet()) {
                String msg = progressErrors.get(fieldId);
                log.error("Workflow transition (progress) had a problem with the workflow field ["
                        + fieldId + "] : message was " + msg);
                return;
            }
        }

        MutableIssue progressedIssue = ComponentAccessor.getIssueManager().getIssueObject(issueToSetStatusOn.getId());
        log.debug("Workflow transition completed successfully: "
                + progressedIssue.toString());
    }

    private void reindexIssue(Issue issue) {
        try {
            boolean origVal = ImportUtils.isIndexIssues();
            ImportUtils.setIndexIssues(true);
            IssueIndexManager indexManager= ComponentAccessor.getIssueIndexManager();
            indexManager.reIndex(issue);
            ImportUtils.setIndexIssues(origVal);
        } catch (IndexException ie) {
            log.error("Unable to reindex issue: " + issue.getKey()
                    + ", [id=" + issue.getId() + "].", ie);
        }
    }

    String getStrippedEmailBody(Message message, Boolean registerSenderInCommentText) throws MessagingException
    {
        String body="";
        if (htmlFirst == true)
            body = HtmlMailUtils.getBody(message, htmlFirst);
        else
            body = MailUtils.getBody(message);
        if (registerSenderInCommentText) {
            body = body + "\n[Commented via e-mail ";
            if ((message.getFrom() != null) && (message.getFrom().length > 0))
                body = body + "received from: " + message.getFrom()[0] + "]";
            else {
                body = body + "but could not establish sender's address.]";
            }

        }

        return stripQuotedLines(body);
    }

    private IssueWorkflowManager getIssueWorkflowManager(User u)
    {
        IssueManager im = ComponentAccessor.getIssueManager();
        WorkflowManager wfm = ComponentAccessor.getWorkflowManager();

        JiraAuthenticationContext jac = ComponentAccessor.getJiraAuthenticationContext();
        jac.setLoggedInUser(u);

        @SuppressWarnings("UnnecessaryLocalVariable")
        IssueWorkflowManager iwm = new IssueWorkflowManagerImpl(im, wfm, jac);

        return iwm;
    }
    /**
     * Determines if the given message is acceptable for handling based on the presence of any JIRA fingerprint headers
     * and this {@link com.atlassian.jira.service.util.handler.MessageHandler}'s configuration.
     *
     * @param message the message to check.
     * @return false only if this handler should not handle the message because of a JIRA fingerprint.
     */
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

     @SuppressWarnings("ConstantConditions")
     String stripQuotedLines(String body)
    {
        if (body == null) {
            return null;
        }
        StringTokenizer st = new StringTokenizer(body, "\n", true);
        StringBuilder result = new StringBuilder();

        boolean strippedAttribution = false;
        boolean outlookQuotedLine = false;

        String line1;
        String line2 = null;
        String line3 = null;
        do
        {
            line1 = line2;
            line2 = line3;
            line3 = st.hasMoreTokens() ? st.nextToken() : null;
            if (!"\n".equals(line3))
            {
                if (st.hasMoreTokens()) st.nextToken();
            }
            if (!strippedAttribution)
            {
                if (!outlookQuotedLine) {
                    outlookQuotedLine = isOutlookQuotedLine(line1);
                }

                if (isQuotedLine(line3))
                {
                    if (looksLikeAttribution(line1))
                        line1 = "> ";
                    else if (looksLikeAttribution(line2)) line2 = "> ";
                    strippedAttribution = true;
                }
            }
            if ((line1 != null) && (!isQuotedLine(line1)) && (!outlookQuotedLine))
            {
                result.append(line1);
                if (!"\n".equals(line1)) result.append("\n");
            }
        }
        while ((line1 != null) || (line2 != null) || (line3 != null));
        return result.toString();
    }

    private boolean isOutlookQuotedLine(String line)
    {
        Iterator iterator;
        if (line != null)
        {
            for (iterator = getOutlookQuoteSeparators().iterator(); iterator.hasNext(); )
            {
                String message = (String)iterator.next();
                if (line.contains(message)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isQuotedLine(String line)
    {
        //noinspection RedundantIfStatement
        if ((line != null) && ((line.startsWith(">")) || (line.startsWith("|")))) {
            return true;
        }
        return false;
    }

    private boolean looksLikeAttribution(String line)
    {
        //noinspection RedundantIfStatement
        if ((line != null) && ((line.endsWith(":")) || (line.endsWith(":\r")))) return true;
        return false;
    }

    private Collection getOutlookQuoteSeparators()
    {
        if (this.messages == null)
        {
            this.messages = new LinkedList<String>();
            BufferedReader reader = null;
            try
            {
                reader = new BufferedReader(new InputStreamReader(ClassLoaderUtils.getResourceAsStream("outlook-email.translations", getClass()), "UTF-8"));
                String message;
                while ((message = reader.readLine()) != null)
                {
                    this.messages.add(message);
                }

            }
            catch (IOException e)
            {
                this.log.error("Error occurred while reading file 'outlook-email.translations'.");
            }
            finally
            {
                try
                {
                    if (reader != null)
                        reader.close();
                }
                catch (IOException e)
                {
                    this.log.error("Could not close the file 'outlook-email.translations'.");
                }
            }
        }

        return this.messages;
    }

}
