package com.ideotechnologies.jira.handler;

/**
 * Created with IntelliJ IDEA.
 * User: s.genin
 * Date: 04/07/13
 * Time: 09:48
 * To change this template use File | Settings | File Templates.
 */

import com.atlassian.mail.MailUtils;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;

import javax.mail.*;
import java.io.*;

// TODO: Doesn't handle charsets/encoding very well. Or, indeed, at all.
/**
 * This class contains a bunch of static helper methods that make life a bit easier particularly with the processing of
 * Parts.
 */
public class HtmlMailUtils
{
    private static final String DEFAULT_ENCODING = "ISO-8859-1";

    static final int BUFFER_SIZE = 64 * 1024;
    static final String MULTIPART_ALTERNATE_CONTENT_TYPE = "multipart/alternative";
    static final String MULTIPART_RELATED_CONTENT_TYPE = "multipart/related";
    static final String MULTIPART_MIXED_CONTENT_TYPE = "multipart/mixed";
    static final String TEXT_CONTENT_TYPE = "text/plain";
    static final String MESSAGE_CONTENT_TYPE = "message/rfc822";
    static final String HTML_CONTENT_TYPE = "text/html";
    static final String CONTENT_TYPE_X_PKCS7 = "application/x-pkcs7-signature";
    static final String CONTENT_TYPE_PKCS7 = "application/pkcs7-signature";

    private static final Logger log = Logger.getLogger(HtmlMailUtils.class);

    /**
     * The content transfer encoding header, which is used to identify whether a part is base64 encoded.
     */
    private static final String CONTENT_TRANSFER_ENCODING_HEADER = "Content-Transfer-Encoding";

    /**
     * Content header id
     */
    private static final String CONTENT_ID_HEADER = "Content-ID";

     /**
     * Get the body of the message as a String. The algorithm for finding the body is as follows:
     *
     * <ol><li>If the message is a single part, and that part is text/plain, return it.
     *     <li>If the message is a single part, and that part is text/html, convert it to
     *         text (stripping out the HTML) and return it.
     *     <li>If the message is multi-part, return the first text/plain part that isn't marked
     *         explicitly as an attachment.
     *     <li>If the message is multi-part, but does not contain any text/plain parts, return
     *         the first text/html part that isn't marked explicitly as an attachment, converting
     *         it to text and stripping the HTML.
     *     <li>If nothing is found in any of the steps above, return null.
     * </ol>
     *
     * <p>Note: If the message contains nested multipart parts, an HTML part nested at a higher level will
     * take precedence over a text part nested deeper.
     *
     * @param message The message to retrieve the body from
     * @return The message body, or null if the message could not be parsed
     * @throws javax.mail.MessagingException If there was an error getting the content from the message
     */
    public static String getBody(Message message, Boolean htmlFirst) throws MessagingException
    {
        log.warn("getBody(message)");
        try
        {
            String content = extractTextFromPart(message,htmlFirst);

            if (content == null)
            {
                log.warn("content is null");
                if (message.getContent() instanceof Multipart)
                {
                    log.debug("calling getBodyFromMultipart");
                    content = getBodyFromMultipart((Multipart) message.getContent(),htmlFirst);
                }
            }

            if (content == null)
            {
                //didn't match anything above
                log.info("Could not find any body to extract from the message");
            }

            return content;
        }
        catch (ClassCastException cce)
        {
            log.info("Exception getting the content type of message - probably not of type 'String': " + cce.getMessage());
            return null;
        }
        catch (IOException e)
        {
            log.info("IOException whilst getting message content " + e.getMessage());
            return null;
        }
    }

    private static String getBodyFromMultipart(Multipart multipart, Boolean htmlFirst) throws MessagingException, IOException
    {
        StringBuffer sb = new StringBuffer();
        getBodyFromMultipart(multipart, sb, htmlFirst);
        return sb.toString();
    }

    private static void getBodyFromMultipart(Multipart multipart, StringBuffer sb, Boolean htmlFirst) throws MessagingException, IOException
    {
        String multipartType = multipart.getContentType();
        log.debug("getBodyFromMultipart");

        // if an multipart/alternative type we just get the first text or html content found
        if(multipartType != null && compareContentType(multipartType, MULTIPART_ALTERNATE_CONTENT_TYPE))
        {
            log.warn("We found an alternate Content Type");

            BodyPart part = getFirstInlinePartWithMimeType(multipart, MULTIPART_RELATED_CONTENT_TYPE);

            if (part != null && part.getContent() instanceof Multipart){
                getBodyFromMultipart((Multipart)part.getContent(),sb,htmlFirst);
            }

            else {
                part = getFirstInlinePartWithMimeType(multipart, MULTIPART_MIXED_CONTENT_TYPE);
                if (part != null && part.getContent() instanceof Multipart){
                    getBodyFromMultipart((Multipart)part.getContent(),sb,htmlFirst);
                }
                else {

                    String firstPartType="";
                    String secondPartType="";

                    if (htmlFirst==true) {
                        firstPartType=HTML_CONTENT_TYPE;
                        secondPartType=TEXT_CONTENT_TYPE;
                    }
                    else {
                        firstPartType=TEXT_CONTENT_TYPE;
                        secondPartType=HTML_CONTENT_TYPE;
                    }


                    part = getFirstInlinePartWithMimeType(multipart, firstPartType);

                    if(part != null)
                    {
                        appendMultipartText(extractTextFromPart(part,htmlFirst), sb);
                    }
                    else
                    {
                        part = getFirstInlinePartWithMimeType(multipart, secondPartType);
                        appendMultipartText(extractTextFromPart(part,htmlFirst), sb);
                    }
                }
            }
            return;
        }

        // otherwise assume multipart/mixed type and construct the contents by retrieving all text and html
        for (int i = 0, n = multipart.getCount(); i < n; i++)
        {
            BodyPart part = multipart.getBodyPart(i);
            String contentType = part.getContentType();

            if (!Part.ATTACHMENT.equals(part.getDisposition()) && contentType != null)
            {
                try
                {
                    String content = extractTextFromPart(part,htmlFirst);
                    if (content != null)
                    {
                        appendMultipartText(content, sb);
                    }
                    else if(part.getContent() instanceof Multipart)
                    {
                        getBodyFromMultipart((Multipart) part.getContent(), sb, htmlFirst);
                    }
                }
                catch (IOException exception)
                {
                    // We swallow the exception because we want to allow processing to continue
                    // even if there is a bad part in one part of the message
                    log.warn("Error retrieving content from part '" + exception.getMessage() + "'", exception);
                }
            }
        }
    }

    private static void appendMultipartText(String content, StringBuffer sb) throws IOException, MessagingException
    {
        if (content != null)
        {
            if(sb.length() > 0) sb.append("\n");
            sb.append(content);
        }
    }

    private static String extractTextFromPart(Part part, Boolean htmlFormat) throws IOException, MessagingException,
            UnsupportedEncodingException
    {
        if (part == null)
            return null;

        String content = null;

        if (MailUtils.isPartPlainText(part))
        {
            try
            {
                content = (String) part.getContent();
                if (htmlFormat) {
                    content="{noformat}"+content+"{noformat}";
                }
            }
            catch (UnsupportedEncodingException e)
            {
                // If the encoding is unsupported read the content with default encoding
                log.warn("Found unsupported encoding '" + e.getMessage() + "'. Reading content with "
                        + DEFAULT_ENCODING + " encoding.");
                content = getBody(part, DEFAULT_ENCODING);
                if (htmlFormat) {
                    content="{noformat}"+content+"{noformat}";
                }
            }
        }
        else if (MailUtils.isPartHtml(part))
        {
            content=(String)part.getContent();
            content=content.replaceAll("\\<base.*?>","");
            content="{html}"+content+"{html}";

        }

        if (content == null)
        {
            log.warn("Unable to extract text from MIME part with Content-Type '" + part.getContentType());
        }

        return content;
    }

    private static String getBody(Part part, String charsetName) throws UnsupportedEncodingException,
            IOException, MessagingException
    {
        Reader input = null;
        StringWriter output = null;
        try
        {
            input = new BufferedReader(new InputStreamReader(part.getInputStream(), charsetName));
            output = new StringWriter();
            IOUtils.copy(input, output);
            return output.getBuffer().toString();
        }
        finally
        {
            IOUtils.closeQuietly(input);
            IOUtils.closeQuietly(output);
        }
    }

    private static BodyPart getFirstInlinePartWithMimeType(Multipart multipart, String mimeType) throws MessagingException
    {
        for (int i = 0, n = multipart.getCount(); i < n; i++)
        {
            BodyPart part = multipart.getBodyPart(i);
            String contentType = part.getContentType();
            if (!Part.ATTACHMENT.equals(part.getDisposition()) && contentType != null && compareContentType(contentType, mimeType))
            {
                return part;
            }
        }
        return null;
    }

    private static boolean compareContentType(String contentType, String mimeType)
    {
        return contentType.toLowerCase().startsWith(mimeType);
    }


}
