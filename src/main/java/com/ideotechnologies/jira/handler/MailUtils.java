package com.ideotechnologies.jira.handler;

/**
 * Created with IntelliJ IDEA.
 * User: s.genin
 * Date: 04/07/13
 * Time: 09:48
 * To change this template use File | Settings | File Templates.
 */

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;
import org.apache.log4j.Logger;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.activation.FileDataSource;
import javax.mail.*;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeUtility;
import java.io.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringTokenizer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

// TODO: Doesn't handle charsets/encoding very well. Or, indeed, at all.
/**
 * This class contains a bunch of static helper methods that make life a bit easier particularly with the processing of
 * Parts.
 */
public class MailUtils
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

    private static final HtmlToTextConverter htmlConverter = new HtmlToTextConverter();
    private static final Logger log = Logger.getLogger(MailUtils.class);

    /**
     * The content transfer encoding header, which is used to identify whether a part is base64 encoded.
     */
    private static final String CONTENT_TRANSFER_ENCODING_HEADER = "Content-Transfer-Encoding";

    /**
     * Content header id
     */
    private static final String CONTENT_ID_HEADER = "Content-ID";

    /**
     * Very simple representation of a mail attachment after it has been
     * extracted from a message.
     */
    public static class Attachment {
        private final String contentType;
        private final String fileName;
        private final byte[] contents;

        public Attachment(String contentType, String fileName, byte[] contents)
        {
            this.contentType = contentType;
            this.fileName = fileName;
            this.contents = contents;
        }

        public String getContentType()
        {
            return contentType;
        }

        public byte[] getContents()
        {
            return contents;
        }

        public String getFilename()
        {
            return fileName;
        }
    }

    /**
     * Parse addresses from a comma (and space) separated string into the proper array
     */
    public static InternetAddress[] parseAddresses(String addresses) throws AddressException
    {
        List<InternetAddress> list = new ArrayList<InternetAddress>();
        list.clear();
        StringTokenizer st = new StringTokenizer(addresses, ", ");
        while (st.hasMoreTokens())
        {
            list.add(new InternetAddress(st.nextToken()));
        }
        return list.toArray(new InternetAddress[list.size()]);
    }

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
    public static String getBody(Message message) throws MessagingException
    {
        log.warn("getBody(message)");
        try
        {
            String content = extractTextFromPart(message);

            if (content == null)
            {
                log.warn("content is null");
                if (message.getContent() instanceof Multipart)
                {
                    log.warn("calling getBodyFromMultipart");
                    content = getBodyFromMultipart((Multipart) message.getContent());
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

    /**
     * Gets all parts of a message that are attachments rather than alternative inline bits.
     *
     * @param message the message from which to extract the attachments
     * @return an array of the extracted attachments
     */
    public static Attachment[] getAttachments(Message message) throws MessagingException, IOException
    {
        List<Attachment> attachments = new ArrayList<Attachment>();

        if (message.getContent() instanceof Multipart)
        {
            addAttachments(attachments, (Multipart)message.getContent());
        }

        return attachments.toArray(new Attachment[attachments.size()]);
    }

    private static void addAttachments(List<Attachment> attachments, Multipart parts) throws MessagingException, IOException
    {
        for (int i = 0, n = parts.getCount(); i < n; i++)
        {
            BodyPart part = parts.getBodyPart(i);

            if (isAttachment(part))
            {
                InputStream content = part.getInputStream();
                String contentType = part.getContentType();

                attachments.add(new Attachment(contentType, part.getFileName(), toByteArray(content)));
            }
            else
            {
                try
                {
                    if (part.getContent() instanceof Multipart)
                    {
                        addAttachments(attachments, (Multipart) part.getContent());
                    }
                }
                catch (UnsupportedEncodingException e)
                {
                    // ignore because it's probably not a multipart part anyway
                    // if the encoding is unsupported
                    log.warn("Unsupported encoding found for part while trying to discover attachments. "
                            + "Attachment will be ignored.", e);
                }
            }
        }
    }

    private static boolean isAttachment(BodyPart part)
            throws MessagingException
    {
        return Part.ATTACHMENT.equals(part.getDisposition()) || Part.INLINE.equals(part.getDisposition())
                || (part.getDisposition() == null && part.getFileName() != null);
    }

    /**
     * Convert the contents of an input stream into a byte array.
     *
     * @param in
     * @return the contents of that stream as a byte array.
     */
    private static byte[] toByteArray(InputStream in) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[512];
        int count;
        while ((count = in.read(buf)) != -1)
        {
            out.write(buf, 0, count);
        }

        out.close();
        return out.toByteArray();
    }

    /**
     * @return true if at least one of the recipients matches the email address given.
     */
    public static boolean hasRecipient(String matchEmail, Message message) throws MessagingException
    {
        Address[] addresses = message.getAllRecipients();

        if (addresses == null || addresses.length == 0)
            return false;

        for (int i = 0; i < addresses.length; i++)
        {
            InternetAddress email = (InternetAddress) addresses[i];

            if (matchEmail.compareToIgnoreCase(email.getAddress()) == 0)
                return true;
        }

        return false;
    }

    /**
     * Returns a List<String> of trimmed non-null email addresses from the
     * given potentially dirty pile of addresses listed as senders on the
     * given message.
     * @param message the message from which to get senders.
     * @return a nice List<String> of email addresses.
     * @throws MessagingException if the senders can't be retrieved from message.
     */
    public static List<String> getSenders(Message message) throws MessagingException
    {

        ArrayList<String> senders = new ArrayList<String>();
        Address[] addresses = message.getFrom();
        if (addresses != null)
        {
            for (int i = 0; i < addresses.length; i++)
            {
                if (addresses[i] instanceof InternetAddress)
                {
                    InternetAddress addr = (InternetAddress) addresses[i];
                    // Trim down the email address to remove any whitespace etc.
                    String emailAddress = StringUtils.trimToNull(addr.getAddress());
                    if (emailAddress != null)
                    {
                        senders.add(emailAddress);
                    }
                }
            }
        }
        return senders;
    }

    /**
     * Produces a mimebodypart object from an attachment file path. An attachment needs to be in this form to be attached
     * to an email for sending
     *
     * @param path
     * @return
     * @throws MessagingException
     */
    public static MimeBodyPart createAttachmentMimeBodyPart(String path) throws MessagingException
    {
        MimeBodyPart attachmentPart = new MimeBodyPart();
        DataSource source = new FileDataSource(path);
        attachmentPart.setDataHandler(new DataHandler(source));

        String fileName = extractFilenameFromPath(path);

        attachmentPart.setFileName(fileName);
        return attachmentPart;
    }

    private static String extractFilenameFromPath(String path) {
        if (path == null) return null;
        StringTokenizer st = new StringTokenizer(path, "\\/");

        String fileName;
        do
        {
            fileName = st.nextToken();
        }
        while (st.hasMoreTokens());
        return fileName;
    }

    public static MimeBodyPart createZippedAttachmentMimeBodyPart(String path) throws MessagingException
    {
        File tmpFile = null;
        String fileName = extractFilenameFromPath(path);

        try {
            tmpFile = File.createTempFile("atlassian", null);
            FileOutputStream fout = new FileOutputStream(tmpFile);
            ZipOutputStream zout = new ZipOutputStream(fout);
            zout.putNextEntry(new ZipEntry(fileName));

            InputStream in = new FileInputStream(path);
            final byte[] buffer = new byte[ BUFFER_SIZE ];
            int n = 0;
            while ( -1 != (n = in.read(buffer)) ) {
                zout.write(buffer, 0, n);
            }
            zout.close();
            in.close();
            log.debug("Wrote temporary zip of attachment to " + tmpFile);
        } catch (FileNotFoundException e) {
            String err = "Couldn't find file '"+path+"' on server: "+e;
            log.error(err, e);
            MimeBodyPart mimeBodyPart = new MimeBodyPart();
            mimeBodyPart.setText(err);
            return mimeBodyPart;
        } catch (IOException e) {
            String err = "Error zipping log file '"+path+"' on server: "+e;
            log.error(err, e);
            MimeBodyPart mimeBodyPart = new MimeBodyPart();
            mimeBodyPart.setText(err);
            return mimeBodyPart;
        }
        MimeBodyPart attachmentPart = new MimeBodyPart();
        DataSource source = new FileDataSource(tmpFile);
        attachmentPart.setDataHandler(new DataHandler(source));
        attachmentPart.setFileName(fileName+".zip");
        attachmentPart.setHeader("Content-Type", "application/zip");
        return attachmentPart;
    }

    private static String getBodyFromMultipart(Multipart multipart) throws MessagingException, IOException
    {
        StringBuffer sb = new StringBuffer();
        getBodyFromMultipart(multipart, sb);
        return sb.toString();
    }

    private static void getBodyFromMultipart(Multipart multipart, StringBuffer sb) throws MessagingException, IOException
    {
        String multipartType = multipart.getContentType();
        log.warn("getBodyFromMultipart");

        // if an multipart/alternative type we just get the first text or html content found
        if(multipartType != null && compareContentType(multipartType, MULTIPART_ALTERNATE_CONTENT_TYPE))
        {
            log.warn("We found an alternate Content Type");

//            BodyPart part = getFirstInlinePartWithMimeType(multipart, TEXT_CONTENT_TYPE)
            BodyPart part = getFirstInlinePartWithMimeType(multipart, MULTIPART_RELATED_CONTENT_TYPE);

            if (part != null && part.getContent() instanceof Multipart){
                getBodyFromMultipart((Multipart)part.getContent(),sb);
//                Multipart multipart1=(Multipart)part.getContent();
//                part = getFirstInlinePartWithMimeType(multipart1, HTML_CONTENT_TYPE);

//                if (part!= null)
//                {
//                    log.warn("We found an HTML Content Type inside");
//                    appendMultipartText(extractTextFromPart(part), sb);
//                }
//                else
//                {
//                    part = getFirstInlinePartWithMimeType(multipart1, TEXT_CONTENT_TYPE);
//                    appendMultipartText(extractTextFromPart(part), sb);
//                }
            }

            else {
                part = getFirstInlinePartWithMimeType(multipart, MULTIPART_MIXED_CONTENT_TYPE);
                if (part != null && part.getContent() instanceof Multipart){
                    getBodyFromMultipart((Multipart)part.getContent(),sb);
                }
                else {
                    part = getFirstInlinePartWithMimeType(multipart, HTML_CONTENT_TYPE);

                    if(part != null)
                    {
                        log.warn("We found an HTML Content Type inside");
                        appendMultipartText(extractTextFromPart(part), sb);
                    }
                    else
                    {
//                    part = getFirstInlinePartWithMimeType(multipart, HTML_CONTENT_TYPE);
                        part = getFirstInlinePartWithMimeType(multipart, TEXT_CONTENT_TYPE);
                        appendMultipartText(extractTextFromPart(part), sb);
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
                    String content = extractTextFromPart(part);
                    if (content != null)
                    {
                        appendMultipartText(content, sb);
                    }
                    else if(part.getContent() instanceof Multipart)
                    {
                        getBodyFromMultipart((Multipart) part.getContent(), sb);
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

    private static String extractTextFromPart(Part part) throws IOException, MessagingException,
            UnsupportedEncodingException
    {
        if (part == null)
            return null;

        String content = null;

        if (isPartPlainText(part))
        {
            try
            {
                content = (String) part.getContent();
            }
            catch (UnsupportedEncodingException e)
            {
                // If the encoding is unsupported read the content with default encoding
                log.warn("Found unsupported encoding '" + e.getMessage() + "'. Reading content with "
                        + DEFAULT_ENCODING + " encoding.");
                content = getBody(part, DEFAULT_ENCODING);
            }
        }
        else if (isPartHtml(part))
        {
            content=(String)part.getContent();
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


    /**
     * Tests if a particular part content type is text/html.
     *
     * @param part The part being tested.
     * @return true if the part content type is text/html
     * @throws MessagingException if javamail complains
     */
    static public boolean isPartHtml(final Part part) throws MessagingException
    {
        final String contentType = MailUtils.getContentType(part);
        return HTML_CONTENT_TYPE.equalsIgnoreCase(contentType);
    }


    /**
     * Tests if the provided part content type is text/plain.
     *
     * @param part The part being tested.
     * @return true if the part content type is text/plain
     * @throws MessagingException if javamail complains
     */
    static public boolean isPartPlainText(final Part part) throws MessagingException
    {
        final String contentType = MailUtils.getContentType(part);
        return TEXT_CONTENT_TYPE.equalsIgnoreCase(contentType);
    }

    /**
     * Tests if the provided part's content type is message/rfc822
     *
     * @param part The part being tested.
     * @return true if the part content type is message/rfc822
     * @throws MessagingException if javamail complains
     */
    static public boolean isPartMessageType(final Part part) throws MessagingException
    {
        // currently, only "message/rfc822" content type is supported
        final String contentType = MailUtils.getContentType(part);
        return MESSAGE_CONTENT_TYPE.equalsIgnoreCase(contentType);
    }

    /**
     * Tests if the provided part's content type is multipart/related
     *
     * @param part The part being tested.
     * @return true if the part content type is multipart/related
     * @throws MessagingException if javamail complains
     */
    static public boolean isPartRelated(final Part part) throws MessagingException
    {
        final String contentType = getContentType(part);
        return MULTIPART_RELATED_CONTENT_TYPE.equalsIgnoreCase(contentType);
    }

    /**
     * Helper which returns the pure mime/subMime content type less any other extra parameters which may
     * accompany the header value.
     *
     * @param part the mail part to extract the content-type from.
     * @return the pure mime/subMime type
     * @throws MessagingException if retrieving the part's Content-Type header fails
     */
    static public String getContentType(final Part part) throws MessagingException
    {
        checkPartNotNull(part);

        final String contentType = part.getContentType();
        return getContentType(contentType);
    }

    /**
     * Helper which extracts the content type from a header value removing parameters and so on.
     *
     * @param headerValue The header value.
     * @return The actual content type
     */
    static public String getContentType(final String headerValue)
    {
        checkHeaderValue(headerValue);

        String out = headerValue;

        final int semiColon = headerValue.indexOf(';');
        if (-1 != semiColon)
        {
            out = headerValue.substring(0, semiColon);
        }

        return out.trim();
    }

    static private void checkHeaderValue(final String headerValue)
    {
        Validate.notEmpty(headerValue);
    }

    /**
     * Tests if the content of the part content is empty.  The definition of empty depends on whether the content is text
     * or binary.
     * <p/>
     * Text content for content types like plain/text and html/text is defined as being empty if it contains an empty string
     * after doing a trim(). If the string contains 50 spaces it is still empty whilst a string with a solitary "a"
     * isnt.
     * <p/>
     * For binary content (like images) if the content contains 0 bytes it is empty whilst anything with 1 or more bytes
     * is NOT considered empty.
     *
     * @param part a mail part - may or may not have content.
     * @return true/false if the content is deemed empty as per above rules.
     * @throws MessagingException if retrieving content fails.
     * @throws IOException        if retrieving content fails or reading content input stream fails.
     */
    static public boolean isContentEmpty(final Part part) throws MessagingException, IOException
    {
        checkPartNotNull(part);

        boolean definitelyEmpty = false;
        final Object content = part.getContent();
        if (null == content)
        {
            definitelyEmpty = true;
        }
        else
        {
            if (content instanceof String)
            {
                final String stringContent = (String) content;
                definitelyEmpty = StringUtils.isBlank(stringContent);
            }

            if (content instanceof InputStream)
            {
                final InputStream inputStream = (InputStream) content;
                try
                {

                    // try and read a byte.. it we get one its not empty, if we dont its empty.
                    final int firstByte = inputStream.read();
                    definitelyEmpty = -1 == firstByte;

                }
                finally
                {
                    IOUtils.closeQuietly(inputStream);
                }
            }
        }

        return definitelyEmpty;
    }

    /**
     * Asserts that the part parameter is not null, throwing a NullPointerException if the part parameter is null.
     *
     * @param part The parameter part
     */
    static private void checkPartNotNull(final Part part)
    {
        Validate.notNull(part, "part should not be null.");
    }

    /**
     * This method uses a number of checks to determine if the given part actually represents an inline (typically image) part.
     * Some email clients (aka lotus notes) dont appear to correctly set the disposition to inline so a number of
     * additional checks are required, hence the multi staged approached.
     * <p/>
     * eg. inline images from notes wont have a inline disposition but will have a content id and will also have their
     * content base64 encoded. This approach helps us correctly identify inline images or other binary parts.
     *
     * @param part The part being tested.
     * @return True if the part is inline false in all other cases.
     * @throws MessagingException as thrown by java mail
     */
    static public boolean isPartInline(final Part part) throws MessagingException
    {
        checkPartNotNull(part);

        boolean inline = false;

        // an inline part is only considered inline if its also got a filename...
        final String disposition = part.getDisposition();
        if (Part.INLINE.equalsIgnoreCase(disposition))
        {
            final String file = part.getFileName();
            if(!StringUtils.isBlank(file))
            {
                inline = true;
            }
            return inline;
        }

        final boolean gotContentId = MailUtils.hasContentId(part);
        if (!gotContentId)
        {
            return false;
        }
        final boolean gotBase64 = MailUtils.isContentBase64Encoded(part);
        if (!gotBase64)
        {
            return false;
        }

        return true;
    }

    static private boolean hasContentId(final Part part) throws MessagingException
    {
        boolean gotContentId = false;
        final String[] contentIds = part.getHeader(MailUtils.CONTENT_ID_HEADER);
        if (null != contentIds)
        {
            for (int i = 0; i < contentIds.length; i++)
            {
                final String contentId = contentIds[i];
                if (contentId != null && contentId.length() > 0)
                {
                    gotContentId = true;
                    break;
                }
            } // for
        }
        return gotContentId;
    }

    /**
     * Checks if a part's content is base64 encoded by scanning for a content transfer encoding header value.
     *
     * @param part THe part being tested.
     * @return True if the content is base 64 encoded, false in all other cases.
     * @throws MessagingException if javamail complains
     */
    static private boolean isContentBase64Encoded(final Part part) throws MessagingException
    {
        boolean gotBase64 = false;
        final String[] contentTransferEncodings = part.getHeader(CONTENT_TRANSFER_ENCODING_HEADER);
        if (null != contentTransferEncodings)
        {
            for (int i = 0; i < contentTransferEncodings.length; i++)
            {
                final String contentTransferEncoding = contentTransferEncodings[i];
                if ("base64".equals(contentTransferEncoding))
                {
                    gotBase64 = true;
                    break;
                }
            }
        }

        return gotBase64;
    }

    /**
     * Tests if the provided part is an attachment. Note this method does not test if the content is empty etc it merely
     * tests whether or not the part is an attachment of some sort.
     *
     * @param part The part being tested.
     * @throws MessagingException if javamail complains
     * @returns True if the part is an attachment otherwise returns false
     */
    static public boolean isPartAttachment(final Part part) throws MessagingException
    {
        checkPartNotNull(part);
        return Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition());
    }

    /**
     * This method may be used to fix any mime encoded filenames that have been returned by javamail.
     * No harm can occur from calling this method unnecessarily except for wasting a few cpu cycles...
     * <p/>
     * Very probably a MIME-encoded filename - see http://java.sun.com/products/javamail/FAQ.html#encodefilename
     *
     * @param filename
     * @return The fixed filename.
     * @throws IOException {@see MimeUtility#decodeText}
     */
    static public String fixMimeEncodedFilename(final String filename) throws IOException
    {
        String newFilename = filename;
        if (filename.startsWith("=?") || filename.endsWith("?="))
        {
            newFilename = MimeUtility.decodeText(filename);
        }
        return newFilename;
    }


    /**
     * Tests if a part is actually a signature. This is required to fix JRA-9933.
     *
     * @param part a mail part. The part is assumed to have a content-type header.
     * @return true if the content-type header matches the standard PKCS7 mime types
     * @throws MessagingException if retrieving the Content-Type from the part fails.
     */
    static public boolean isPartSignaturePKCS7(final Part part) throws MessagingException
    {
        MailUtils.checkPartNotNull(part);
        final String contentType = MailUtils.getContentType(part).toLowerCase(Locale.getDefault());
        return contentType.startsWith(CONTENT_TYPE_PKCS7) || contentType.startsWith(CONTENT_TYPE_X_PKCS7);
    }

    /**
     * Get the local host name from InetAddress and return it in a form suitable for use in an email address or Message-ID.
     * <p>
     * Inspired by {@link javax.mail.internet.InternetAddress#getLocalAddress(javax.mail.Session)} and required because
     * {@link javax.mail.internet.InternetAddress#getLocalHostName()} is private.
     */
    @SuppressWarnings ("UnusedDeclaration")
    public static String getLocalHostName()
    {
        String host = null;
        InetAddress localHostAddress;
        try
        {
            localHostAddress = InetAddress.getLocalHost();
        }
        catch (UnknownHostException e)
        {
            return "localhost";
        }
        if (localHostAddress != null) {
            host = localHostAddress.getHostName();
            if (host != null && host.length() > 0 && isInetAddressLiteral(host))
            {
                // required for an email address, not sure if it is required for a Message-ID, but no harm and this is
                // what JavaMail would do.
                host = '[' + host + ']';
            }
        }
        if (host == null)
            return "localhost";
        else
            return host;
    }

    /**
     * Is the address an IPv4 or IPv6 address literal, which needs to
     * be enclosed in "[]" in an email address?  IPv4 literals contain
     * decimal digits and dots, IPv6 literals contain hex digits, dots,
     * and colons.  We're lazy and don't check the exact syntax, just
     * the allowed characters; strings that have only the allowed
     * characters in a literal but don't meet the syntax requirements
     * for a literal definitely can't be a host name and thus will fail
     * later when used as an address literal.
     *
     * (Copied from javax.mail.internet.InternetAddress)
     */
    private static boolean isInetAddressLiteral(String addr)
    {
        boolean sawHex = false, sawColon = false;
        for (int i = 0; i < addr.length(); i++) {
            char c = addr.charAt(i);
            if (c >= '0' && c <= '9')
                ;	// digits always ok
            else if (c == '.')
                ;	// dot always ok
            else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))
                sawHex = true;	// need to see a colon too
            else if (c == ':')
                sawColon = true;
            else
                return false;	// anything else, definitely not a literal
        }
        return !sawHex || sawColon;
    }

}
