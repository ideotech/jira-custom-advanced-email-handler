package com.ideotechnologies.jira.handler;

import com.atlassian.crowd.embedded.api.User;
import com.atlassian.jira.JiraApplicationContext;
import com.atlassian.jira.bc.project.component.ProjectComponent;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.config.ConstantsManager;
import com.atlassian.jira.config.properties.ApplicationProperties;
import com.atlassian.jira.event.type.EventType;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueFactory;
import com.atlassian.jira.issue.IssueFieldConstants;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.comments.CommentManager;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.SummarySystemField;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.priority.Priority;
import com.atlassian.jira.issue.security.IssueSecurityLevel;
import com.atlassian.jira.issue.security.IssueSecurityLevelManager;
import com.atlassian.jira.issue.util.IssueUpdater;
import com.atlassian.jira.mail.MailThreadManager;
import com.atlassian.jira.plugin.assignee.AssigneeResolver;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.security.Permissions;
import com.atlassian.jira.service.util.handler.MessageHandlerErrorCollector;
import com.atlassian.jira.user.UserUtils;
import com.atlassian.jira.web.FieldVisibilityManager;
import com.atlassian.mail.MailUtils;
import com.google.common.collect.Lists;
import com.opensymphony.util.TextUtils;
import org.apache.log4j.Logger;

import javax.mail.Address;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Part;
import javax.mail.internet.InternetAddress;
import java.io.IOException;
import java.util.*;


/**
 * A message handler to create a new issue from an incoming message. 
 * Note: requires public noArg constructor as this
 * class is instantiated by reflection.
 *
 *
 */
@SuppressWarnings({"BooleanMethodIsAlwaysInverted", "SameParameterValue"})
public class AdvancedCreateIssueHandler extends AbstractAdvancedEmailHandler {

    private static final Logger log = Logger.getLogger(AdvancedCreateIssueHandler.class);
    private IssueDescriptor issueDescriptor;
    private String issueType;                 // chosen issue type
    private String defaultProjectKey = null;  // default project where new issues are created
    private String defaultIssueType = null;   // default type for new issues
    private String defaultComponentName = null;   // default component for new issues in the default project
    private boolean ccAssignee = true;        // first Cc user becomes the assignee ?
    private String excludeAddress;


    AdvancedCreateIssueHandler(CommentManager commentManager, IssueFactory issueFactory, ApplicationProperties applicationProperties, JiraApplicationContext jiraApplicationContext, AssigneeResolver assigneeResolver, FieldVisibilityManager fieldvisibilityManager, IssueUpdater issueUpdater) {
        super(commentManager, issueFactory, applicationProperties, jiraApplicationContext, assigneeResolver, fieldvisibilityManager,issueUpdater);
    }

    public void init(Map<String, String> params, MessageHandlerErrorCollector messageHandlerErrorCollector) {
        //To change body of implemented methods use File | Settings | File Templates.

        log.debug("CreateIssueHandler.init(params: " + params + ")");

        super.init(params);

        if (params.containsKey(Settings.KEY_FSPACK))  {
            ccAssignee=false;
        }

        if (params.containsKey(Settings.KEY_PROJECT)) {
            this.defaultProjectKey = params.get(Settings.KEY_PROJECT);
        }

        if (params.containsKey(Settings.KEY_ISSUETYPE)) {
            this.defaultIssueType = params.get(Settings.KEY_ISSUETYPE);
        }

        if (params.containsKey(Settings.CC_ASSIGNEE)) {
            ccAssignee = Boolean.valueOf(params.get(Settings.CC_ASSIGNEE));
        }

        if (params.containsKey(Settings.KEY_COMPONENT)) {
            defaultComponentName = params.get(Settings.KEY_COMPONENT);
        }

        if (params.containsKey(Settings.KEY_EXCLUDEADDRESS))
        {
            excludeAddress = params.get(Settings.KEY_EXCLUDEADDRESS);
        }

        log.debug("Params: " + this.defaultProjectKey + " - " + this.defaultIssueType + " - " + this.ccAssignee + " - " + this.defaultComponentName);
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    public boolean handleMessage(Message message, com.atlassian.jira.service.util.handler.MessageHandlerContext context)
            throws MessagingException
    {
        log.debug("AdvancedCreateIssueHandler.handleMessage");

        if (issueDescriptor.getIssueType() != null) {
            issueType = issueDescriptor.getIssueType();
        } else {
            issueType = defaultIssueType;
        }

        try {
            Boolean securityLevelSet = false;
            String reporterName = issueDescriptor.getReporter();
            // Sets the reporter specified in the tag, or the sender of the message,
            // or the default reporter
            User reporter = (
                    reporterName != null && getUserByName(reporterName) != null ?
                            getUserByName(reporterName) :
                            getReporter(message)
            );
            if (reporter == null) {
                String errorMessage = "Sender is anonymous, no default reporter specified and creating users " +
                        "is set to false (or external user management is enabled). Message rejected.";
                log.warn(errorMessage);
                return false;
            }

            if ((excludeAddress!=null) && (reporter.getName().compareTo(excludeAddress) == 0)) {
                String errorMessage = "Message coming from the excluded address. Message rejected.";
                log.warn(errorMessage);
                return true;            // the message shall be deleted as it is not a configuration issue

            }

            Project project = getProject();
            log.debug("Project = " + project);
            boolean usingDefaultProject = false;
            if (project == null) {
                project = getProjectManager().getProjectObjByKey(defaultProjectKey.toUpperCase());
                if(! (project == null) ) {
                    usingDefaultProject = true;
                }
            }
            if (project == null) {
                String errorMessage = "Cannot handle message as destination project is null and no default project set";
                log.warn(errorMessage);
                return false;
            }

            log.debug("Issue Type Key = = " + issueType);
            if (!hasValidIssueType()) {
                String errorMessage = "Cannot handle message as Issue Type is null or invalid";
                log.warn(errorMessage);
                return false;
            }
            String summary = issueDescriptor.getSummary();
            if (!TextUtils.stringSet(summary)) { // should never happen
                log.warn("Issue must have a summary. The mail message has an empty or no subject.");
                return false;
            }
            if (summary.length() > SummarySystemField.MAX_LEN.intValue()) {
                log.warn("Truncating summary field because it is too long: " + summary);
                summary = summary.substring(0, SummarySystemField.MAX_LEN.intValue() - 3) + "...";
            }

            // JRA-7646 - check if priority/description is hidden - if so, do not set
            String priority = null;
            String description = null;

            if (!fieldVisibilityManager.isFieldHiddenInAllSchemes(project.getId(), IssueFieldConstants.PRIORITY, Lists.newArrayList(issueType))) {
                if(issueDescriptor.getPriorityId() != null){
                    priority = issueDescriptor.getPriorityId();
                }else{
                    priority = getDefaultSystemPriority();
                }
            }

            if (!fieldVisibilityManager.isFieldHiddenInAllSchemes(project.getId(), IssueFieldConstants.DESCRIPTION, Lists.newArrayList(issueType))) {
                description = getDescription(reporter, message);
            }

            MutableIssue issueObject = ComponentAccessor.getIssueFactory().getIssue();

            issueObject.setProjectId(project.getId());
            issueObject.setSummary(summary);

            description+=getAttachmentNamesIfNecessary(message);

            issueObject.setDescription(description);
            issueObject.setIssueTypeId(issueType);
            issueObject.setReporter(reporter);

            if (issueDescriptor.getDueDate() != null) {
                issueObject.setDueDate(issueDescriptor.getDueDate());
            }
            if (issueDescriptor.getOriginalEstimate() != null) {
                issueObject.setOriginalEstimate(issueDescriptor.getOriginalEstimate());
            }

            // Sets the assignee specified in the tag, or the first valid Cc assignee,
            // or else the default assignee
            String assigneeName = issueDescriptor.getAssignee();
            User assignee = null;
            if (
                    assigneeName != null &&
                            getUserByName(assigneeName) != null &&
                            isValidAssignee(project, getUserByName(assigneeName))) {
                assignee = getUserByName(assigneeName);
            } else {
                if (ccAssignee) {
                    assignee = getFirstValidAssignee(message.getAllRecipients(), project);
                }
            }
            if (assignee == null) {
                assignee =  assigneeResolver.getDefaultAssigneeObject(issueObject, Collections.EMPTY_MAP);
            }
            if (assignee != null) {
                issueObject.setAssignee(assignee);
            }

            issueObject.setPriorityId(priority);

            // Sets components to the issue
            if (issueDescriptor.getComponents() != null) {   // The component name
                String[] comps = issueDescriptor.getComponents();
                Collection<ProjectComponent> components = new ArrayList<ProjectComponent>();
                for (String comp : comps) {

                    ProjectComponent component = ComponentAccessor.getProjectComponentManager().findByComponentName(project.getId(), comp);
                    if (component != null) {
                        components.add(component);
                    }
                }
                if (components.size() > 0) {
                    issueObject.setComponentObjects(components);
                }
            } else {
                // If no component was specified and we are using the default project
                // Try and set the default component
                ProjectComponent defaultComponent = null;
                if(usingDefaultProject && project != null){
                    // Try and set a default component too
                    defaultComponent = ComponentAccessor.getProjectComponentManager().findByComponentName(project.getId(), defaultComponentName);
                    if(defaultComponent == null){
                        String errorMessage = "Cannot set default component on new issue as component does not exist";
                        log.info(errorMessage);
                    }
                }

                if(defaultComponent != null){
                    issueObject.setComponentObjects(Arrays.asList(new ProjectComponent[]{defaultComponent}));
                }

            }


            // Here, we check the group membership, and find the related IssueSecurityLevel
            // With name=groupName

            IssueSecurityLevelManager issueSecurityLevelManager= ComponentAccessor.getIssueSecurityLevelManager();

            Collection<IssueSecurityLevel> issueSecurityLevels=issueSecurityLevelManager.getUsersSecurityLevels(project,reporter);

            //noinspection LoopStatementThatDoesntLoop
            for(IssueSecurityLevel issueSecurityLevel:issueSecurityLevels){
                issueObject.setSecurityLevelId(issueSecurityLevel.getId());
                securityLevelSet = true;
                break;
            }

            // Ensure issue level security is correct
            if (!securityLevelSet)
                setDefaultSecurityLevel(issueObject);

            Map<String,Object> fields = new HashMap<String,Object>();
            fields.put("issue", issueObject);

            // Give the CustomFields a chance to set their default values JRA-11762
            List customFieldObjects = ComponentAccessor.getCustomFieldManager().getCustomFieldObjects(issueObject);
            for (Object customFieldObject : customFieldObjects) {
                CustomField customField = (CustomField) customFieldObject;
                issueObject.setCustomFieldValue(customField, customField.getDefaultValue(issueObject));
            }

            Issue issue = ComponentAccessor.getIssueManager().createIssueObject(reporter,fields);

            if (issue != null) {
                log.info("Issue " + issue.getKey() + " created");

                // Record the message id of this e-mail message so we can track replies to this message
                // and associate them with this issue
                recordMessageId(MailThreadManager.MailAction.ISSUE_CREATED_FROM_EMAIL, message, issue);
            }

            addCCUserToCustomField(message,issueObject);
            createAttachmentsForMessage(message, issue);

            ComponentAccessor.getIssueEventManager().dispatchEvent(EventType.ISSUE_CREATED_ID,issue,reporter,true);

            return true;
        }
        catch (Exception e) {
            String errorMessage = "Could not create issue!";
            log.error(errorMessage, e);
        }

        // something went wrong - don't delete the message
        return false;
    }


    Project getProject() {
        String pKey = issueDescriptor.getProjectKey();
        if (
                pKey != null &&
                        !pKey.equals("") &&
                        getProjectManager().getProjectObjByKey(pKey.toUpperCase()) != null) {
            return getProjectManager().getProjectObjByKey(pKey.toUpperCase());
        } else {
            return null;
        }
    }


    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    boolean hasValidIssueType() {
        // if there is no project then the issue cannot be created
        if (issueType == null) {
            log.debug("Issue Type NOT set. Cannot find Issue type.");
            return false;
        }

        IssueType issueTypeObject = ComponentAccessor.getConstantsManager().getIssueTypeObject(issueType);
        if (issueTypeObject == null) {
            log.debug("Issue Type does not exist with id of " + issueType);
            return false;
        }

        log.debug("Issue Type Object = " + issueTypeObject.getName());
        return true;
    }


    ProjectManager getProjectManager() {
        return ComponentAccessor.getProjectManager();
    }


    /**
     * Extracts the description of the issue from the message.
     *
     * @param reporter  the established reporter of the issue
     * @param message  the message from which the issue is created
     * @return the description of the issue
     * @throws MessagingException
     */
    private String getDescription(User reporter, Message message) throws MessagingException {
        if (htmlFirst)
            return recordFromAddressForAnon(reporter, message, HtmlMailUtils.getBody(message, htmlFirst));
        else
            return recordFromAddressForAnon(reporter, message, MailUtils.getBody(message));
    }


    /**
     * Adds the senders' From: addresses to the end of the issue's details (if they could be extracted), if the e-mail
     * has been received from an unknown e-mail address and the mapping to an "anonymous" user has been enabled.
     *
     * @param reporter  the established reporter of the issue (after one has been established)
     * @param message  the message that is used to create issue
     * @param description  the issues extracted description
     * @return the modified description if the e-mail is from anonymous user, unmodified description otherwise
     * @throws MessagingException
     */
    private String recordFromAddressForAnon(User reporter, Message message, String description) throws MessagingException {
        // If the message has been created for an anonymous user add the senders e-mail address to the description.
        if (reporterUsername != null && reporterUsername.equals(reporter.getName())) {
            description += "\n[Created via e-mail ";
            if (message.getFrom() != null && message.getFrom().length > 0) {
                description += "received from: " + message.getFrom()[0] + "]";
            } else {
                description += "but could not establish sender's address.]";
            }
        }
        return description;
    }




    private String getDefaultSystemPriority() {
        // if priority header is not set, assume it's 'default'
        ConstantsManager constantsManager = ComponentAccessor.getConstantsManager();
        Priority defaultPriority = constantsManager.getDefaultPriorityObject();
        if (defaultPriority != null) {
            return defaultPriority.getId();
        } else {
            log.error("Default priority was null. Using the 'middle' priority.");

            Collection<Priority> priorities = constantsManager.getPriorityObjects();
            Iterator<Priority> priorityIt = priorities.iterator();
            int times = (int) Math.ceil((double) priorities.size() / 2d);
            for (int i = 0; i < times; i++) {
                defaultPriority = priorityIt.next();
            }

            //noinspection ConstantConditions
            if (defaultPriority != null)
                return defaultPriority.getId();
            else
                return null;
        }
    }


    /**
     * Given an array of addresses, returns the first valid assignee for the appropriate project.
     *
     * @param addresses  the addresses
     * @param project  the project
     * @return  the first valid assignee for <code>project</code>
     */
    private static User getFirstValidAssignee(Address[] addresses, Project project)
    {
        if (addresses == null || addresses.length == 0) {
            return null;
        }

        for (Address address : addresses) {
            if (address instanceof InternetAddress) {
                InternetAddress email = (InternetAddress) address;

                User validUser = UserUtils.getUserByEmail(email.getAddress());
                if (isValidAssignee(project, validUser)) {
                    return validUser;
                }
            }
        }

        return null;
    }


    /**
     * Tells if <code>user</code> is a valid assignee for <code>project</code>.
     *
     * @param project  a project
     * @param user  an user
     * @return  whether <code>user</code> is a valid assignee for <code>project</code>
     */
    private static boolean isValidAssignee(Project project, User user) {
        return (ComponentAccessor.getPermissionManager().hasPermission(Permissions.ASSIGNABLE_USER, project, user));
    }


    /**
     * Returns an <code>User</code> given its <code>userName</code>.
     *
     * @param userName  the name of the user
     * @return  the user, or <code>null</code> if there was no user with name <code>userName</code>
     */
    private static User getUserByName(String userName) {

       return UserUtils.getUser(userName);
    }


    private void setDefaultSecurityLevel(MutableIssue issue) {
        Project project = issue.getProjectObject();
        if (project != null) {
            IssueSecurityLevelManager issueSecurityLevelManager = ComponentAccessor.getIssueSecurityLevelManager();
            final Long levelId = issueSecurityLevelManager.getDefaultSecurityLevel(project);
            if (levelId != null) {
                issue.setSecurityLevelId(issueSecurityLevelManager.getSecurityLevel(levelId).getId());
            }
        }
    }

    final void setIssueDescriptor(IssueDescriptor issueDescriptor) {
        this.issueDescriptor = issueDescriptor;
    }

     void setJiraEmail(String email){
       jiraEmail=email;
    }


    /**
     * Attaches plaintext parts.
     * Text parts are not attached but rather potentially form the source of issue text.
     * However text part attachments are kept providing they are not empty.
     *
     * @param part  the part which will have a content type of text/plain to be tested
     * @return  true if the part is an attachment and not empty
     * @throws  MessagingException
     * @throws  IOException
     */
    protected boolean attachPlainTextParts(final Part part) throws MessagingException, IOException
    {
        return !MailUtils.isContentEmpty(part) && MailUtils.isPartAttachment(part);
    }

    /**
     * Attaches HTML parts.
     * HTML parts are not attached but rather potentially form the source of issue text.
     * However html part attachments are kept providing they are not empty.
     *
     * @param part  the part which will have a content type of text/html to be tested
     * @return  true if the part is an attachment and not empty
     * @throws  MessagingException
     * @throws  IOException
     */
    protected boolean attachHtmlParts(final Part part) throws MessagingException, IOException
    {
        return !MailUtils.isContentEmpty(part) && MailUtils.isPartAttachment(part);
    }


}
