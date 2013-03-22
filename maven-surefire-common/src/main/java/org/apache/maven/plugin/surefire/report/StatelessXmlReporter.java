package org.apache.maven.plugin.surefire.report;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Enumeration;
import java.util.Properties;
import java.util.StringTokenizer;
import org.apache.maven.shared.utils.io.IOUtil;
import org.apache.maven.shared.utils.xml.XMLWriter;
import org.apache.maven.surefire.report.ReportEntry;
import org.apache.maven.surefire.report.ReporterException;
import org.apache.maven.surefire.report.SafeThrowable;

/**
 * XML format reporter writing to <code>TEST-<i>reportName</i>[-<i>suffix</i>].xml</code> file like written and read
 * by Ant's <a href="http://ant.apache.org/manual/Tasks/junit.html"><code>&lt;junit&gt;</code></a> and
 * <a href="http://ant.apache.org/manual/Tasks/junitreport.html"><code>&lt;junitreport&gt;</code></a> tasks,
 * then supported by many tools like CI servers.
 * <p/>
 * <pre>&lt;?xml version="1.0" encoding="UTF-8"?>
 * &lt;testsuite name="<i>suite name</i>" [group="<i>group</i>"] tests="<i>0</i>" failures="<i>0</i>" errors="<i>0</i>" skipped="<i>0</i>" time="<i>0,###.###</i>">
 *  &lt;properties>
 *    &lt;property name="<i>name</i>" value="<i>value</i>"/>
 *    [...]
 *  &lt;/properties>
 *  &lt;testcase time="<i>0,###.###</i>" name="<i>test name</i> [classname="<i>class name</i>"] [group="<i>group</i>"]"/>
 *  &lt;testcase time="<i>0,###.###</i>" name="<i>test name</i> [classname="<i>class name</i>"] [group="<i>group</i>"]">
 *    &lt;<b>error</b> message="<i>message</i>" type="<i>exception class name</i>"><i>stacktrace</i>&lt;/error>
 *    &lt;system-out><i>system out content (present only if not empty)</i>&lt;/system-out>
 *    &lt;system-err><i>system err content (present only if not empty)</i>&lt;/system-err>
 *  &lt;/testcase>
 *  &lt;testcase time="<i>0,###.###</i>" name="<i>test name</i> [classname="<i>class name</i>"] [group="<i>group</i>"]">
 *    &lt;<b>failure</b> message="<i>message</i>" type="<i>exception class name</i>"><i>stacktrace</i>&lt;/failure>
 *    &lt;system-out><i>system out content (present only if not empty)</i>&lt;/system-out>
 *    &lt;system-err><i>system err content (present only if not empty)</i>&lt;/system-err>
 *  &lt;/testcase>
 *  &lt;testcase time="<i>0,###.###</i>" name="<i>test name</i> [classname="<i>class name</i>"] [group="<i>group</i>"]">
 *    &lt;<b>skipped</b>/>
 *  &lt;/testcase>
 *  [...]</pre>
 *
 * @author Kristian Rosenvold
 * @see <a href="http://wiki.apache.org/ant/Proposals/EnhancedTestReports">Ant's format enhancement proposal</a>
 *      (not yet implemented by Ant 1.8.2)
 */
public class StatelessXmlReporter
{

    private final File reportsDirectory;

    private final String reportNameSuffix;

    private final boolean trimStackTrace;

    private final String encoding = "UTF-8";

    public StatelessXmlReporter( File reportsDirectory, String reportNameSuffix, boolean trimStackTrace )
    {
        this.reportsDirectory = reportsDirectory;
        this.reportNameSuffix = reportNameSuffix;
        this.trimStackTrace = trimStackTrace;
    }

    public void testSetCompleted( WrappedReportEntry testSetReportEntry, TestSetStats testSetStats )
        throws ReporterException
    {

        OutputStreamWriter fw = getWriter( testSetReportEntry );
        try
        {

            org.apache.maven.shared.utils.xml.XMLWriter ppw =
                new org.apache.maven.shared.utils.xml.PrettyPrintXMLWriter( fw );
            ppw.setEncoding( encoding );

            createTestSuiteElement( ppw, testSetReportEntry, testSetStats, reportNameSuffix );

            showProperties( ppw );

            for ( WrappedReportEntry entry : testSetStats.getReportEntries() )
            {
                if ( ReportEntryType.success.equals( entry.getReportEntryType() ) )
                {
                    startTestElement( ppw, entry, reportNameSuffix );
                    ppw.endElement();
                }
                else
                {
                    getTestProblems( ppw, entry, trimStackTrace, reportNameSuffix );
                }

            }
            ppw.endElement(); // TestSuite

        }
        finally
        {
            IOUtil.close( fw );
        }
    }

    private OutputStreamWriter getWriter( WrappedReportEntry testSetReportEntry )
    {
        File reportFile = getReportFile( testSetReportEntry, reportsDirectory, reportNameSuffix );

        File reportDir = reportFile.getParentFile();

        //noinspection ResultOfMethodCallIgnored
        reportDir.mkdirs();

        try
        {

            FileOutputStream fos = new FileOutputStream( reportFile );

            return new OutputStreamWriter( fos, encoding );
        }
        catch ( IOException e )
        {
            throw new ReporterException( "When writing report", e );
        }
    }

    private File getReportFile( ReportEntry report, File reportsDirectory, String reportNameSuffix )
    {
        File reportFile;

        if ( reportNameSuffix != null && reportNameSuffix.length() > 0 )
        {
            reportFile = new File( reportsDirectory, "TEST-" + report.getName() + "-" + reportNameSuffix + ".xml" );
        }
        else
        {
            reportFile = new File( reportsDirectory, "TEST-" + report.getName() + ".xml" );
        }

        return reportFile;
    }

    private static void startTestElement( XMLWriter ppw, WrappedReportEntry report, String reportNameSuffix )
    {
        ppw.startElement( "testcase" );
        ppw.addAttribute( "name", report.getReportName() );
        if ( report.getGroup() != null )
        {
            ppw.addAttribute( "group", report.getGroup() );
        }
        if ( report.getSourceName() != null )
        {
            if ( reportNameSuffix != null && reportNameSuffix.length() > 0 )
            {
                ppw.addAttribute( "classname", report.getSourceName() + "(" + reportNameSuffix + ")" );
            }
            else
            {
                ppw.addAttribute( "classname", report.getSourceName() );
            }
        }
        ppw.addAttribute( "time", report.elapsedTimeAsString() );
    }

    private static void createTestSuiteElement( XMLWriter ppw, WrappedReportEntry report, TestSetStats testSetStats,
                                                String reportNameSuffix1 )
    {
        ppw.startElement( "testsuite" );

        ppw.addAttribute( "name", report.getReportName( reportNameSuffix1 ) );

        if ( report.getGroup() != null )
        {
            ppw.addAttribute( "group", report.getGroup() );
        }

        ppw.addAttribute( "time", testSetStats.getElapsedForTestSet() );

        ppw.addAttribute( "tests", String.valueOf( testSetStats.getCompletedCount() ) );

        ppw.addAttribute( "errors", String.valueOf( testSetStats.getErrors() ) );

        ppw.addAttribute( "skipped", String.valueOf( testSetStats.getSkipped() ) );

        ppw.addAttribute( "failures", String.valueOf( testSetStats.getFailures() ) );

    }


    private void getTestProblems( XMLWriter ppw, WrappedReportEntry report, boolean trimStackTrace,
                                  String reportNameSuffix )
    {

        startTestElement( ppw, report, reportNameSuffix );

        ppw.startElement( report.getReportEntryType().name() );

        String stackTrace = report.getStackTrace( trimStackTrace );

        if ( report.getMessage() != null && report.getMessage().length() > 0 )
        {
            ppw.addAttribute( "message", extraEscape( report.getMessage(), true ) );
        }

        if ( report.getStackTraceWriter() != null )
        {
            //noinspection ThrowableResultOfMethodCallIgnored
            SafeThrowable t = report.getStackTraceWriter().getThrowable();
            if ( t != null )
            {
                if ( t.getMessage() != null )
                {
                    ppw.addAttribute( "type", ( stackTrace.contains( ":" )
                        ? stackTrace.substring( 0, stackTrace.indexOf( ":" ) )
                        : stackTrace ) );
                }
                else
                {
                    ppw.addAttribute( "type", new StringTokenizer( stackTrace ).nextToken() );
                }
            }
        }

        if ( stackTrace != null )
        {
            ppw.writeText( extraEscape( stackTrace, false ) );
        }

        ppw.endElement(); // entry type

        addOutputStreamElement( ppw, report.getStdout(), "system-out" );

        addOutputStreamElement( ppw, report.getStdErr(), "system-err" );

        ppw.endElement(); // test element
    }

    private void addOutputStreamElement( XMLWriter xmlWriter, String stdOut, String name )
    {
        if ( stdOut != null && stdOut.trim().length() > 0 )
        {
            xmlWriter.startElement( name );
            xmlWriter.writeText( extraEscape( stdOut, false ) );
            xmlWriter.endElement();
        }
    }

    /**
     * Adds system properties to the XML report.
     * <p/>
     *
     * @param xmlWriter The test suite to report to
     */
    private void showProperties( XMLWriter xmlWriter )
    {
        xmlWriter.startElement( "properties" );

        Properties systemProperties = System.getProperties();

        if ( systemProperties != null )
        {
            Enumeration<?> propertyKeys = systemProperties.propertyNames();

            while ( propertyKeys.hasMoreElements() )
            {
                String key = (String) propertyKeys.nextElement();

                String value = systemProperties.getProperty( key );

                if ( value == null )
                {
                    value = "null";
                }

                xmlWriter.startElement( "property" );

                xmlWriter.addAttribute( "name", key );

                xmlWriter.addAttribute( "value", extraEscape( value, true ));

                xmlWriter.endElement();

            }
        }
        xmlWriter.endElement();
    }

    /**
     * Handle stuff that may pop up in java that is not legal in xml
     *
     * @param message   The string
     * @param attribute
     * @return The escaped string
     */
    private static String extraEscape( String message, boolean attribute )
    {
        // Someday convert to xml 1.1 which handles everything but 0 inside string
        if ( !containsEscapesIllegalnXml10( message ) )
        {
            return message;
        }
        return escapeXml( message, attribute );
    }

    private static boolean containsEscapesIllegalnXml10( String message )
    {
        int size = message.length();
        for ( int i = 0; i < size; i++ )
        {
            if ( isIllegalEscape( message.charAt( i ) ) )
            {
                return true;
            }

        }
        return false;
    }

    private static boolean isIllegalEscape( char c )
    {
        return c < 32 && c != '\n' && c != '\r' && c != '\t';
    }

    private static String escapeXml( String text, boolean attribute )
    {
        StringBuilder sb = new StringBuilder( text.length() * 2 );
        for ( int i = 0; i < text.length(); i++ )
        {
            char c = text.charAt( i );
            if ( isIllegalEscape( c ) )
            {
                // uh-oh!  This character is illegal in XML 1.0!
                // http://www.w3.org/TR/1998/REC-xml-19980210#charsets
                // we're going to deliberately doubly-XML escape it...
                // there's nothing better we can do! :-(
                // SUREFIRE-456
                sb.append( attribute ? "&#" : "&amp#" ).append( (int) c ).append(
                    ';' ); // & Will be encoded to amp inside xml encodingSHO
            }
            else
            {
                sb.append( c );
            }
        }
        return sb.toString();
    }

}
