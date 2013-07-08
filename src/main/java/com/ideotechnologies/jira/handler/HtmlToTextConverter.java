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
        ArrayList links = new ArrayList();

        private static final String OPEN_BRACKET = "[";
        private static final String CLOSE_BRACKET = "]";

        // Note: the "position" parameter for all the methods below denotes our
        // character position in the source document. Thus, we ignore it a lot.
        public HTMLCallbackHandler(Writer writer) {
            out = writer;
        }

        public void handleStartTag(HTML.Tag tag, javax.swing.text.MutableAttributeSet set, int position) {
            try
            {
                // We don't handle images in body
                if (!tag.equals(HTML.Tag.IMG))
                    {
                        String setString="";
                        if (set != null) {
                            setString=" "+set.toString()+" " ;

                        out.write("<"+tag.toString()+setString+">");                }
                    }
            }
            catch (IOException e)
            {
                log.warn("IO error converting HTML to text", e);
            }

        }


        public void handleEndTag(HTML.Tag tag, int position) {

            try {
                out.write("</"+tag.toString()+">");
            } catch (IOException e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            }
        }

        public void handleText(char[] aChar, int position) {
            try
            {
                out.write(aChar);

            }
            catch (IOException e)
            {
                log.warn("IO error converting HTML to text", e);
            }
        }

        public void handleSimpleTag(HTML.Tag tag, javax.swing.text.MutableAttributeSet a, int pos) {
            try
            {
                String aString="";
//                if (a != null) {
//                    aString=" "+a.toString()+" " ;
//
//                out.write("<"+tag.toString()+" "+aString+"/>");
//                }
                out.write("<"+tag.toString()+"/>");
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
