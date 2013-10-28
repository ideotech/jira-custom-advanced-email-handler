package com.ideotechnologies.jira.handler;

import com.atlassian.core.util.collection.EasyList;

import java.util.List;

public class Settings
{
    public static final String DEFAULT_SUMMARY = "(no summary)";
    public static final String REGEX_ISSUETYPE_BUG = "#BUG";
    public static final String REGEX_ISSUETYPE_NEW_FEATURE = "#NEWFEATURE";
    public static final String REGEX_ISSUETYPE_TASK = "#TASK";
    public static final String REGEX_ISSUETYPE_IMPROVEMENT = "#IMPROVEMENT";
    public static final String REGEX_ISSUETYPE_SUBTASK = "#SUBTASK";
    public static final String REGEX_PRIORITY_BLOCKER = "#BLOCKER";
    public static final String REGEX_PRIORITY_CRITICAL = "#CRITICAL";
    public static final String REGEX_PRIORITY_MAJOR = "#MAJOR";
    public static final String REGEX_PRIORITY_MINOR = "#MINOR";
    public static final String REGEX_PRIORITY_TRIVIAL = "#TRIVIAL";
    public static final String REGEX_WORKFLOW_TARGET = "#WORKFLOW=";
    public static final String REGEX_WORKFLOW_RESOLVE = "#RESOLVE";
    public static final String REGEX_WORKFLOW_CLOSE = "#CLOSE";
    public static final String REGEX_WORKFLOW_RESOLUTION = "#RESOLUTION=";
    public static final String REGEX_COMPONENT = "#COMPONENT=";
    public static final String REGEX_PROJECTKEY = "#PROJECT=";
    public static final String REGEX_ASSIGNEE = "#ASSIGNEE=";
    public static final String REGEX_REPORTER = "#REPORTER=";
    public static final String REGEX_DUEDATE = "#DUE=";
    public static final String REGEX_ESTIMATE = "#EST=";
    public static final String KEY_PROJECT = "project";
    public static final String KEY_ISSUETYPE = "issuetype";
    public static final String KEY_QUOTES = "stripquotes";
    public static final String KEY_JIRAEMAIL = "jiraemail";
    public static final String KEY_JIRAALIAS = "jiraalias";
    public static final String KEY_WHITELIST = "whitelist";
    public static final String KEY_SUBJECTREGEXP = "subjectregexp";
    public static final String KEY_SUBJECTREPLACE = "subjectreplace";
    public static final String KEY_REPORTERUSERNAME = "reporterusername";
    public static final String KEY_CUSTOMFIELD = "customfield";
    public static final String CC_ASSIGNEE = "ccAssignee";
    public static final String KEY_COMPONENT = "component";
    public static final String HEADER_MESSAGE_ID = "message-id";
    public static final String HEADER_IN_REPLY_TO = "in-reply-to";
    public static final String ATTACHED_MESSAGE_FILENAME = "attachedmessage";
    public static final String DEFAULT_BINARY_FILE_NAME = "binary.bin";
    public static final char INVALID_CHAR_REPLACEMENT = '_';
    public static final String KEY_CREATEUSERS = "createusers";
    public static final String KEY_USERGROUP = "adduserstogroup";
    public static final String KEY_NOTIFYUSERS = "notifyusers";
    public static final String KEY_CATCHEMAIL = "catchemail";
    public static final String KEY_FINGER_PRINT = "fingerprint";
    public static final String VALUE_FINGER_PRINT_ACCEPT = "accept";
    public static final String VALUE_FINGER_PRINT_FORWARD = "forward";
    public static final String VALUE_BULK_IGNORE = "ignore";
    public static final String VALUE_FINGER_PRINT_IGNORE = "ignore";
    public static final List VALUES_FINGERPRINT = EasyList.build(VALUE_FINGER_PRINT_ACCEPT, VALUE_FINGER_PRINT_FORWARD, VALUE_FINGER_PRINT_IGNORE);
    public static final String VALUE_BULK_FORWARD = "forward";
    public static final String VALUE_BULK_DELETE = "delete";
    public static final String KEY_BULK = "bulk";
    public static final String FALSE = "false";
    public static final String TRUE = "true";
    public static final String KEY_CCFIELD="ccField";
    public static final String KEY_EXCLUDEADDRESS="excludeaddress";
    public static final String KEY_REFERENCEATTACHMENTS="referenceattachments";
    public static final String KEY_HTMLFIRST = "htmlfirst";
    public static final String KEY_FORCEPROJECT = "forceproject";
    public static final String KEY_FSPACK = "fspack";

}