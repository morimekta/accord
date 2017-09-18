/*
 * Created on Jan 22, 2005
 */
package net.morimekta.util.std;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

/**
 * @author morimekta
 */
public class Log {
    private final int        max_write_queue = 5;
    private BufferedWriter   writer  = null;
//    private PrintWriter      print   = null;
    private DateFormat       format  = null;
    private int              count   = 0;
	/**
     * Get the logging level.
     * 
	 * @return Returns the logging.
	 */
	public Level getLoggingLevel() {
		return loggingLevel;
	}
	/**
     * Sets the logging level.
     * 
	 * @param logging The logging to set.
	 */
	public void setLoggingLevel(Level logging) {
		this.loggingLevel = logging;
	}
	/**
	 * @return Returns the verbose.
	 */
	public Level getVerboseLevel() {
		return verboseLevel;
	}
	/**
     * Set the verbority level.
     * 
	 * @param verbose The verbose to set.
	 */
	public void setVerboseLevel(Level verbose) {
		this.verboseLevel = verbose;
	}
    
    private Level            loggingLevel = Level.fatal;
    private Level            verboseLevel = Level.high;
    
    /**
     * Levels of logging. Note that the levels "none" and "all" are not for
     * logging messages, only for logging level. All others can be used wherever
     * wanted and needed.
     */
    public enum Level {
        /**
         * meta level, do no logging.
         */
        none,
        /**
         * Fatal loggings only.
         */
        fatal,
        /**
         * Errors.
         */
        error,
        /**
         * Warnings.
         */
        warning,
        /**
         * High important messages.
         */
        high,
        /**
         * Medium importance messages.
         */
        medium,
        /**
         * Low Importance messages.
         */
        low,
        /**
         * Meta level, log all messages.
         */
        all
    }
    
    /**
     * Array of strings with "short" note of severity.
     */
    private static String[] OPS = {
        "<>", // NONE // what kind of message is none by the way???
        "FF", // FATAL
        "EE", // ERROR
        "WW", // WARNING
        "**", // HIGH
        "--", // MEDIUM
        "  ", // LOW
        "[]", // ALL
    };
    /**
     * Create a log in the file 'name'.
     * 
     * @param name 
     *     Name of file.
     * @throws IOException 
     */
    public Log( String name ) throws IOException {
    	File       file      = new File(name);
        if( !file.exists() )   file.createNewFile();
        FileWriter filewr    = new FileWriter(file);
        writer               = new BufferedWriter(filewr);
//        print                = new PrintWriter(writer);
    	// build date-time formatter.
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Oslo"));
            format = DateFormat.getDateTimeInstance();
            ((SimpleDateFormat) format).applyPattern("yy.MM.dd HH:mm:ss.SSS");
        } catch( Exception e ) {
            System.err.println("Caught Exception in LogFile: "+e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Write an entry in the logfile.
     * 
     * @param arg0
     *     String to write in logfile.
     * @param lvl
     *     Importance of entry to log. 
     */
    public synchronized void log(String arg0, Level lvl) {
        long     millis  = System.currentTimeMillis();
        String[] lines   = arg0.split("\n");
        String   part    =
            format.format(millis)+
            " ("+OPS[lvl.ordinal()]+") "+
            Thread.currentThread().getName()+
            ": ";
        for( int i = 0; i < lines.length; i++ ) {
            String str = part+lines[i];
            if( lvl.ordinal() <= verboseLevel.ordinal() ){
                System.out.println(str);
            }
            if( lvl.ordinal() <= loggingLevel.ordinal() ) {
            	try {
                    writer.write(str);
                    writer.newLine();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        // TODO: Make fewer log entries ??
        try {
            writer.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Close the log file.
     *
     */
    public void close() {
        log("Closing Log.", Level.low);
        try {
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
