package com.ideotechnologies.jira.handler;

/**
 * Created with IntelliJ IDEA.
 * User: s.genin
 * Date: 04/07/13
 * Time: 09:52
 * To change this template use File | Settings | File Templates.
 */

import org.apache.log4j.Category;

import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.*;
import java.util.ArrayList;

/**
 * Helper class to convert arbitrary HTML documents into text. Conversion is very basic, intended
 * to be used to strip markup from HTML-only emails for inclusion within JIRA issue reports or
 * comments.
 */
public class HtmlToTextConverter
{
    private static final Category log = Category.getInstance(HtmlToTextConverter.class);

    private class HTMLCallbackHandler extends HTMLEditorKit.ParserCallback {

        Writer out;
        boolean started = false;
        boolean inBody = false;
        boolean inList = false;
        boolean firstTD = true;
        int listCount = -1;
        ArrayList links = new ArrayList();

        private static final String NEWLINE = "\n";
        private static final String TAB = "\t";
        private static final String STAR = "*";
        private static final String SPACE = " ";
        private static final String PERIOD = ".";
        private static final String OPEN_BRACKET = "[";
        private static final String CLOSE_BRACKET = "]";
        private static final String DASH_LINE = "----------------------------------------------------------------------------------------";

        // Note: the "position" parameter for all the methods below denotes our
        // character position in the source document. Thus, we ignore it a lot.
        public HTMLCallbackHandler(Writer writer) {
            out = writer;
        }

        public void handleStartTag(HTML.Tag tag, javax.swing.text.MutableAttributeSet set, int position) {
            try
            {
                if (inBody && started && tag.equals(HTML.Tag.P))
                {
                    out.write(NEWLINE + NEWLINE);
                }
                else if (inBody && started && tag.equals(HTML.Tag.OL) || tag.equals(HTML.Tag.UL))
                {
                    inList = true;
                    out.write(NEWLINE + NEWLINE);
                    if(tag.equals(HTML.Tag.OL))
                        listCount = 1;
                }
                else if (inBody && started && inList && tag.equals(HTML.Tag.LI))
                {
                    out.write(NEWLINE);
                    if(listCount != -1)
                    {
                        out.write(listCount + PERIOD + SPACE);
                        listCount++;
                    }
                    else
                        out.write(STAR);
                }
                else if (inBody && started && tag.equals(HTML.Tag.TABLE))
                {
                    out.write(NEWLINE);
                }
                else if (inBody && started && tag.equals(HTML.Tag.TR))
                {
                    out.write(NEWLINE);
                    firstTD = true;
                }
                else if (inBody && started && tag.equals(HTML.Tag.TD) || tag.equals(HTML.Tag.TH))
                {
                    if(!firstTD)
                    {
                        out.write(TAB);
                    }
                    else
                    {
                        firstTD = false;
                    }
                }
                else if (inBody && started && tag.equals(HTML.Tag.PRE))
                {
                    out.write(NEWLINE);
                }
                else if (inBody && started && tag.equals(HTML.Tag.IMG))
                {
                    // Check if the img has a src attribute
                    handleLink((String)set.getAttribute(HTML.Attribute.SRC));
                }
                else if (inBody && started && tag.equals(HTML.Tag.A))
                {
                    // Check if the img has a src attribute
                    handleLink((String)set.getAttribute(HTML.Attribute.HREF));
                }
                else if (inBody && started && tag.equals(HTML.Tag.HR))
                {
                    out.write(NEWLINE + DASH_LINE);
                }
                else if (inBody && started && tag.equals(HTML.Tag.H1) || tag.equals(HTML.Tag.H2) || tag.equals(HTML.Tag.H3) || tag.equals(HTML.Tag.H4) || tag.equals(HTML.Tag.H5) || tag.equals(HTML.Tag.H6))
                {
                    out.write(NEWLINE);
                }
                else if (tag.equals(HTML.Tag.BODY))
                {
                    inBody = true;
                }
            }
            catch (IOException e)
            {
                log.warn("IO error converting HTML to text", e);
            }

        }

        private void handleLink(String src) throws IOException
        {
            if(src != null)
            {
                links.add(src);
                out.write(OPEN_BRACKET + links.size() + CLOSE_BRACKET);
            }
        }

        public void handleEndTag(HTML.Tag tag, int position) {
            if (inBody && started && tag.equals(HTML.Tag.OL) || tag.equals(HTML.Tag.UL))
            {
                inList = false;
                if(tag.equals(HTML.Tag.OL))
                    listCount = -1;
            }
            else if (tag.equals(HTML.Tag.BODY))
            {
                if(links.size() != 0)
                {
                    // write out the links
                    try
                    {
                        out.write(NEWLINE + DASH_LINE + NEWLINE);
                        for (int i = 0; i < links.size(); i++)
                        {
                            String src = (String)links.get(i);
                            out.write(OPEN_BRACKET + (i + 1) + CLOSE_BRACKET + SPACE + src);
                            if((i + 1) < links.size())
                            {
                                out.write(NEWLINE);
                            }
                        }
                    }
                    catch (IOException e)
                    {
                        e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                    }
                }
                inBody = false;
            }
        }

        public void handleText(char[] aChar, int position) {
            try
            {
                if (inBody)
                {
                    out.write(aChar);
                    started = true;
                }
            }
            catch (IOException e)
            {
                log.warn("IO error converting HTML to text", e);
            }
        }

        public void handleSimpleTag(HTML.Tag tag, javax.swing.text.MutableAttributeSet a, int pos) {
            try
            {
                if (inBody && started && tag.equals(HTML.Tag.BR))
                    out.write(NEWLINE);
            }
            catch (IOException e)
            {
                log.warn("IO error converting HTML to text", e);
            }

        }
    }


    public String convert(String html) throws IOException
    {
        StringWriter out = new StringWriter();
        convert(new StringReader(html), out);
        out.close();
        return out.toString();
    }

    private void convert(Reader reader, Writer writer) throws IOException
    {
        HTMLCallbackHandler handler = new HTMLCallbackHandler(writer);
        new ParserDelegator().parse(reader, handler, true);
    }
}
