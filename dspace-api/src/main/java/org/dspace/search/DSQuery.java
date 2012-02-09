/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.log4j.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.queryParser.TokenMgrError;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Hits;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Searcher;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.core.ConfigurationManager;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.core.LogManager;
import org.dspace.sort.SortOption;

// issues
// need to filter query string for security
// cmd line query needs to process args correctly (seems to split them up)
/**
 * DSIndexer contains various static methods for performing queries on indices,
 * for collections and communities.
 *
 */
public class DSQuery
{
    // Result types
    static final String ALL = "999";

    static final String ITEM = "" + Constants.ITEM;

    static final String COLLECTION = "" + Constants.COLLECTION;

    static final String COMMUNITY = "" + Constants.COMMUNITY;

    // cache a Lucene IndexSearcher for more efficient searches
    private static IndexSearcher searcher = null;

    private static String indexDir = null;
    
    private static String operator = null;
    
    private static long lastModified;
    
    /** log4j logger */
    private static Logger log = Logger.getLogger(DSQuery.class);

    
    static
    {
        String maxClauses = ConfigurationManager.getProperty("search.max-clauses");
        if (maxClauses != null)
        {
            BooleanQuery.setMaxClauseCount(Integer.parseInt(maxClauses));
        } 
        
        indexDir = ConfigurationManager.getProperty("search.dir");
        
        operator = ConfigurationManager.getProperty("search.operator");   
    }

    /**
     * Do a query, returning a QueryResults object
     *
     * @param c  context
     * @param args query arguments in QueryArgs object
     * 
     * @return query results QueryResults
     */
    public static QueryResults doQuery(Context c, QueryArgs args)
            throws IOException
    {
        String querystring = args.getQuery();
        QueryResults qr = new QueryResults();
        List<String> hitHandles = new ArrayList<String>();
        List<Integer> hitIds     = new ArrayList<Integer>();
        List<Integer> hitTypes   = new ArrayList<Integer>();

        // set up the QueryResults object
        qr.setHitHandles(hitHandles);
        qr.setHitIds(hitIds);
        qr.setHitTypes(hitTypes);
        qr.setStart(args.getStart());
        qr.setPageSize(args.getPageSize());
        qr.setEtAl(args.getEtAl());

        // massage the query string a bit
        querystring = checkEmptyQuery(querystring); // change nulls to an empty string
        // We no longer need to work around the Lucene bug with recent versions
        //querystring = workAroundLuceneBug(querystring); // logicals changed to && ||, etc.
        querystring = stripHandles(querystring); // remove handles from query string
        querystring = stripAsterisk(querystring); // remove asterisk from beginning of string
        querystring = stripInterspersedDash(querystring);   // remove all dashes from between terms. Leaves negators.

        try
        {
            // grab a searcher, and do the search
            Searcher searcher = getSearcher(c);

            QueryParser qp = new QueryParser("default", DSIndexer.getAnalyzer());
            log.debug("Final query string: " + querystring);
            
            if (operator == null || operator.equals("OR"))
            {
            	qp.setDefaultOperator(QueryParser.OR_OPERATOR);
            }
            else
            {
            	qp.setDefaultOperator(QueryParser.AND_OPERATOR);
            }
            
            Query myquery = qp.parse(querystring);
            Hits hits = null;

            try
            {
                if (args.getSortOption() == null)
                {
                    SortField[] sortFields = new SortField[] {
                            new SortField("search.resourcetype", true),
                            new SortField(null, SortField.SCORE, SortOption.ASCENDING.equals(args.getSortOrder()))
                        };
                    hits = searcher.search(myquery, new Sort(sortFields));
                }
                else
                {
                    SortField[] sortFields = new SortField[] {
                            new SortField("search.resourcetype", true),
                            new SortField("sort_" + args.getSortOption().getName(), SortOption.DESCENDING.equals(args.getSortOrder())),
                            SortField.FIELD_SCORE
                        };
                    hits = searcher.search(myquery, new Sort(sortFields));
                }
            }
            catch (Exception e)
            {
                // Lucene can throw an exception if it is unable to determine a sort time from the specified field
                // Provide a fall back that just works on relevancy.
                log.error("Unable to use speficied sort option: " + (args.getSortOption() == null ? "type/relevance": args.getSortOption().getName()));
                hits = searcher.search(myquery, new Sort(SortField.FIELD_SCORE));
            }
            
            // set total number of hits
            qr.setHitCount(hits.length());

            // We now have a bunch of hits - snip out a 'window'
            // defined in start, count and return the handles
            // from that window
            // first, are there enough hits?
            if (args.getStart() < hits.length())
            {
                // get as many as we can, up to the window size
                // how many are available after snipping off at offset 'start'?
                int hitsRemaining = hits.length() - args.getStart();

                int hitsToProcess = (hitsRemaining < args.getPageSize()) ? hitsRemaining
                        : args.getPageSize();

                for (int i = args.getStart(); i < (args.getStart() + hitsToProcess); i++)
                {
                    Document d = hits.doc(i);

                    String resourceId   = d.get("search.resourceid");
                    String resourceType = d.get("search.resourcetype");

                    String handleText = d.get("handle");
                    String handleType = d.get("type");

                    switch (Integer.parseInt( resourceType != null ? resourceType : handleType))
                    {
                        case Constants.ITEM:
                            hitTypes.add(Integer.valueOf(Constants.ITEM));
                            break;

                        case Constants.COLLECTION:
                            hitTypes.add(Integer.valueOf(Constants.COLLECTION));
                            break;

                        case Constants.COMMUNITY:
                            hitTypes.add(Integer.valueOf(Constants.COMMUNITY));
                            break;
                    }

                    hitHandles.add( handleText );
                    hitIds.add( resourceId == null ? null: Integer.parseInt(resourceId) );
                }
            }
        }
        catch (NumberFormatException e)
        {
            log.warn(LogManager.getHeader(c, "Number format exception", "" + e));
            qr.setErrorMsg("number-format-exception");
        }
        catch (ParseException e)
        {
            // a parse exception - log and return null results
            log.warn(LogManager.getHeader(c, "Invalid search string", "" + e));
            qr.setErrorMsg("invalid-search-string");
        }
        catch (TokenMgrError tme)
        {
            // Similar to parse exception
            log.warn(LogManager.getHeader(c, "Invalid search string", "" + tme));
            qr.setErrorMsg("invalid-search-string");
        }
        catch(BooleanQuery.TooManyClauses e)
        {
            log.warn(LogManager.getHeader(c, "Query too broad", e.toString()));
            qr.setErrorMsg("query-too-broad");
        }

        return qr;
    }

    static String checkEmptyQuery(String myquery)
    {
        if (myquery == null || myquery.equals("()") || myquery.equals(""))
        {
            myquery = "empty_query_string";
        }

        return myquery;
    }

    /**
     * Workaround Lucene bug that breaks wildcard searching.
     * This is no longer required with Lucene upgrades.
     * 
     * @param myquery
     * @return
     * @deprecated
     */
    static String workAroundLuceneBug(String myquery)
    {
        // Lucene currently has a bug which breaks wildcard
        // searching when you have uppercase characters.
        // Here we substitute the boolean operators -- which
        // have to be uppercase -- before transforming the
        // query string to lowercase.
        return myquery.replaceAll(" AND ", " && ")
                      .replaceAll(" OR ", " || ")
                      .replaceAll(" NOT ", " ! ")
                      .toLowerCase();
    }

    static String stripHandles(String myquery)
    {
        // Drop beginning pieces of full handle strings
        return myquery.replaceAll("^\\s*http://hdl\\.handle\\.net/", "")
                      .replaceAll("^\\s*hdl:", "");
    }

    static String stripAsterisk(String myquery)
    {
        // query strings (or words) beginning with "*" cause a null pointer error
        return myquery.replaceAll("^\\*", "")
                      .replaceAll("\\s\\*", " ")
                      .replaceAll("\\(\\*", "(")
                      .replaceAll(":\\*", ":");
    }

    /**
     * Remove a dash that is inbetween terms. Don't want it to be interpreted as a negator. Negator should touch a term.
     * @param myQuery
     * @return
     */
    static String stripInterspersedDash(String myQuery)
    {
        return myQuery.replaceAll(" - ", " ");
    }

    /**
     * Do a query, restricted to a collection
     * 
     * @param c
     *            context
     * @param args
     *            query args
     * @param coll
     *            collection to restrict to
     * 
     * @return QueryResults same results as doQuery, restricted to a collection
     */
    public static QueryResults doQuery(Context c, QueryArgs args,
            Collection coll) throws IOException
    {
        String querystring = args.getQuery();

        querystring = checkEmptyQuery(querystring);

        String location = "l" + (coll.getID());

        String newquery = "+(" + querystring + ") +location:\"" + location + "\"";

        args.setQuery(newquery);

        return doQuery(c, args);
    }

    /**
     * Do a query, restricted to a community
     * 
     * @param c
     *            context
     * @param args
     *            query args
     * @param comm
     *            community to restrict to
     * 
     * @return QueryResults same results as doQuery, restricted to a collection
     */
    public static QueryResults doQuery(Context c, QueryArgs args, Community comm)
            throws IOException
    {
        String querystring = args.getQuery();

        querystring = checkEmptyQuery(querystring);

        String location = "m" + (comm.getID());

        String newquery = "+(" + querystring + ") +location:\"" + location + "\"";

        args.setQuery(newquery);

        return doQuery(c, args);
    }


    /**
     * Do a query, printing results to stdout largely for testing, but it is
     * useful
     */
    public static void doCMDLineQuery(String query)
    {
        System.out.println("Command line query: " + query);
        System.out.println("Only reporting default-sized results list");

        try
        {
            Context c = new Context();

            QueryArgs args = new QueryArgs();
            args.setQuery(query);

            QueryResults results = doQuery(c, args);

            Iterator i = results.getHitHandles().iterator();
            Iterator j = results.getHitTypes().iterator();

            while (i.hasNext())
            {
                String thisHandle = (String) i.next();
                Integer thisType = (Integer) j.next();
                String type = Constants.typeText[thisType.intValue()];

                // also look up type
                System.out.println(type + "\t" + thisHandle);
            }
        }
        catch (Exception e)
        {
            System.out.println("Exception caught: " + e);
        }
    }

    /**
     * Close any IndexSearcher that is currently open.
     */
    public static synchronized void close()
    {
        if (searcher != null)
        {
            try
            {
                searcher.close();
                searcher = null;
            }
            catch (IOException ioe)
            {
                log.error("DSQuery: Unable to close open IndexSearcher", ioe);
            }
        }
    }
    
    public static void main(String[] args)
    {
        if (args.length > 0)
        {
            DSQuery.doCMDLineQuery(args[0]);
        }
    }

    /*---------  protected methods ----------*/

    /**	
     * get an IndexReader.
     * @throws IOException 
     */
    protected static IndexReader getIndexReader() 
    	throws IOException
    {
    	return getSearcher(null).getIndexReader();
    }
    
    /**
     * get an IndexSearcher, hopefully a cached one (gives much better
     * performance.) checks to see if the index has been modified - if so, it
     * creates a new IndexSearcher
     */
    protected static synchronized IndexSearcher getSearcher(Context c)
            throws IOException
    {
       
        // If we have already opened a searcher, check to see if the index has been updated
        // If it has, we need to close the existing searcher - we will open a new one later
        if (searcher != null && lastModified != IndexReader.getCurrentVersion(indexDir))
        {
            try
            {
                // Close the cached IndexSearcher
                searcher.close();
            }
            catch (IOException ioe)
            {
                // Index is probably corrupt. Log the error, but continue to either:
                // 1) Return existing searcher (may yet throw exception, no worse than throwing here)
                log.warn("DSQuery: Unable to check for updated index", ioe);
            }
            finally
            {
            	searcher = null;
            }
        }

        // There is no existing searcher - either this is the first execution,
        // or the index has been updated and we closed the old index.
        if (searcher == null)
        {
            // So, open a new searcher
            lastModified = IndexReader.getCurrentVersion(indexDir);
            String osName = System.getProperty("os.name");
            if (osName != null && osName.toLowerCase().contains("windows"))
            {
                searcher = new IndexSearcher(indexDir){
                    /*
                     * TODO: Has Lucene fixed this bug yet?
                     * Lucene doesn't release read locks in
                     * windows properly on finalize. Our hack
                     * extend IndexSearcher to force close().
                     */
                    protected void finalize() throws Throwable {
                        this.close();
                        super.finalize();
                    }
                };
            }
            else
            {
                searcher = new IndexSearcher(indexDir);
            }
        }

        return searcher;
    }
}

// it's now up to the display page to do the right thing displaying
// items & communities & collections
