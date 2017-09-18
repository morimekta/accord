package net.morimekta.util.std;
/*
 * Created on 07.jan.2005
 */

import java.util.TreeMap;

/**
 * Option string format: <code>
 * "char,name[,argument]"
 * </code> separated by newlines.
 * 
 * @author Stein Eldar
 */
public abstract class Options {
    
    public  final static char NOOP = '\0';
    
    private String[] last_run;
    private TreeMap<String, Opt> map;
    private Opt[]   arr;
    
    private class Opt {
        char    c;
        String  name;
        boolean arg;
        
        Opt( char _c, String _name, boolean _arg ){
            c        = _c;
            name     = _name;
            arg      = _arg;
        }
    }
    
    /**
     * Create an Options object instance
     * .
     * @param _opts
     *    Options string to generate parser from.
     */
    public Options( String _opts ){
        map = new TreeMap<String, Opt>();
        arr = new Opt[128];
        last_run = null;
        
        String[] args, opts = _opts.split("\n");
        Opt      opt;
        char     c;
        String   name;
        boolean  arg;
        
        for( int i = 0; i < opts.length; i++ ){
            args = opts[i].split(",");
            c    = NOOP;
            name = null;
            arg  = false;
            
            // parse option args
            switch( args.length ){
                default:
                case 3:
                    arg    = true;
                case 2:
                    name   = args[1];
                    c      = args[0].charAt(0);
                    // make opt entry
                    opt    = new Opt(c, name, arg);
                    // add to tables.
                    arr[c] = opt;
                    map.put(name, opt);
                    break;
                case 1:
                case 0:
            }
            
        }
    }
    
    /**
     * Run a string for options. Each option (with or without argument)
     *  is run and sent to the <code>parse</code> method with the option
     *  char and possibly argument.
     * <br>
     * Runs all strings through splitstring and sends them to run(String[])
     *  
     * @param args
     *    String with options, possibly multiple lines.
     */
    public final void run( String args ){
        if( args == null ) return; // stop if no work..
    	String[] arg, lines = args.split("\n");
    	for( int i = 0; i < lines.length; i++ ){
    		if( lines[i] != null ){
        		arg = STD.splitString(lines[i]);
        		run(arg);
        		return;
    		}
    	}
    }
    
    /**
     * Same as run(String), but runs the set of words in the lines.
     * @param args
     *    Array of Strings each sent to run(String).
     * @see Options#run(String)
     */
    public final void run( String[] args ){
        
        last_run = args;
        Opt      op = null;
        String   arg;
        
        int i = 0;
        
        while( i < args.length ){
            if( args[i] == null ){
                // do nothing...
            } else if( args[i].equals("--") ) {
                // end of arguments ...
                break;
            } else if( args[i].startsWith("--") ){ // begin word exists.
                // long argument.
                
                op = (Opt) map.get(args[i].substring(2, args[i].length() ));
                
                if( op != null ){
                    arg = null;
                    if( op.arg ){
                        if( i < (args.length - 1) )
                            arg = args[i+1];
                        i++;
                    }
                    
                    parse( op.c, arg );
                } else {
                    parse( '?', args[i] );
                }
            } else if( args[i].startsWith("-") ){
                // short arg list...
                for( int j = 1; j < args[i].length(); j++ ){
                    op = arr[args[i].charAt(j)];
                    if( op != null ){
                    	if( op.arg &&                      // needs argument
                    	    j == ( args[i].length()-1 ) && // last short arg.
                    	    i < args.length-1){            // have argument.
                    		i++;
                    		arg = args[i];
                    		parse( op.c, arg );
                    		break;
                    	} else
                    		parse( op.c, null );
                    } else
                        parse( '?', "-"+args[i].substring(j,j+1) );
                }
            } else {
                // unknown string, if not empty, parse it.
                if( args[i].length() > 0 )
                    parse( '?', args[i] );
            } // end word exists
            i++;
        }
        
    }
    
    /**
     * Abstract parse method. This is implemented for each options class, and is
     *  called with the option character and the argument if it exists.
     * @param op
     *   Option Char
     * @param arg
     *   Option Argument, or null if none.
     */
    public abstract void parse( char op, String arg );

    /**
     * Return the String of words last used in run(String[]).
     * @return
     *    Last Run array of words sent to run(String[]).
     */
    public String[] getLast_run() {
        return last_run;
    }
    
}
