package com.rs;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.AsyncTask;
import com.rs.TCOfflineStatementCollection.addStatementInterface;
import com.rs.TCOfflineStructures.LocalStateItem;
import com.rs.TCOfflineStructures.LocalStatementsItem;
import com.rusticisoftware.tincan.*;
import com.rusticisoftware.tincan.json.StringOfJSON;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** RSTinCanOfflineConnector is the entry point for interacting with the Offline SDK. Provided here are methods
 * to save statements and state locally and flush stored data to the LRS when connected
 *
 * @author Derek Clark
 * @author Brian Rogers
 * Date: 5/8/13
 *
 * Copyright 2013 Rustici Software
 *
 */
public class RSTinCanOfflineConnector extends Activity {

    Context appContext;
    List<Map<String,String>> _recordStore = new ArrayList<Map<String,String>>();

    /**
     * Create new connector with options and context
     * @param options - should contain 'recordStore' option with LRS, Auth and Version
     * @param context
     */
    public RSTinCanOfflineConnector(Map<String,Object> options, Context context)
    {
        appContext = context;
        _recordStore = (List<Map<String,String>>)options.get("recordStore");
    }

    public interface sendStatementInterface {
        void completionBlock();
        void errorBlock(String error);
    }

    /** sendStatementToServer(Statement statementToSend, sendStatementInterface sendInterface)
     *
     * @param statementToSend	The Statement that you want to send to the LRS
     * @param sendInterface     The callback interface for completion
     *
     */
    void sendStatementToServer(Statement statementToSend, sendStatementInterface sendInterface)
    {
        new sendStatementToServerAsync().execute(statementToSend,sendInterface);
    }



    /** sendStatementToServerAsync() provides the async connection to post the statement
     *
     * @extends AsyncTask
     */
    class sendStatementToServerAsync extends AsyncTask<Object, Integer, Boolean> {

        Exception myE = null;
        sendStatementInterface mySendInterface;


        @Override
        protected Boolean doInBackground(Object... info) {              //background function

            mySendInterface = (sendStatementInterface)info[1];

            RemoteLRS lrs = new RemoteLRS();                                  //get the lrs
            try {
                lrs.setEndpoint(_recordStore.get(0).get("endpoint"));
                lrs.setAuth(_recordStore.get(0).get("auth"));
                lrs.setVersion(TCAPIVersion.fromString(_recordStore.get(0).get("version")));
            }
            catch (Exception e)
            {
                myE = e;
                return true;
            }

            try {

                Statement st = (Statement)info[0];
                lrs.saveStatement(st);                                  //send statement
            }
            catch (Exception e)
            {
                return true;
            }

            return false;   //success


        }

        @Override
        protected void onPostExecute(Boolean failure) {

            if (failure)
            {
                mySendInterface.errorBlock(myE.toString());
            }
            else
            {
                mySendInterface.completionBlock();
            }
        }
    }

    public interface setStateInterface {
        void completionBlock();
        void errorBlock(String error);
    }

    String JSONString(State state)
    {
        StringOfJSON json = new StringOfJSON(state.getContents().toString());
        String returnString = json.toString();
        returnString = returnString.replace("\\", "");
        return returnString;
    }

    /**
     * stringByAddingPercentEscapesUsingEncoding( String input, String charset ) is a port of the iOS utility of the same name
     * @param input
     * @param charset
     * @return string result
     * @throws UnsupportedEncodingException
     */
    public static String stringByAddingPercentEscapesUsingEncoding( String input, String charset ) throws UnsupportedEncodingException {
        byte[] bytes = input.getBytes(charset);
        StringBuilder sb = new StringBuilder(bytes.length);
        for( int i = 0; i < bytes.length; ++i ) {
            int cp = bytes[i] < 0 ? bytes[i] + 256 : bytes[i];
            if( cp <= 0x20 || cp >= 0x7F || (
                    cp == 0x22 || cp == 0x25 || cp == 0x3C ||
                            cp == 0x3E || cp == 0x20 || cp == 0x5B ||
                            cp == 0x5C || cp == 0x5D || cp == 0x5E ||
                            cp == 0x60 || cp == 0x7b || cp == 0x7c ||
                            cp == 0x7d
            )) {
                sb.append( String.format( "%%%02X", cp ) );
            }
            else {
                sb.append( (char)cp );
            }
        }
        return sb.toString();
    }

    /**
     * stringByAddingPercentEscapesUsingEncoding( String input )
     * @param input
     * @return string result
     */
    public static String stringByAddingPercentEscapesUsingEncoding( String input ) {
        try {
            return stringByAddingPercentEscapesUsingEncoding(input, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Java platforms are required to support UTF-8");
            // will never happen
        }
    }

    /**
     * querystring for the state
     * @param state
     * @return querystring for state put
     */
    String querystring(State state)
    {
        String returnString;

        try {
            String agentString = state.getAgent().toJSON().toString();
            agentString = agentString.replace(" ","");

            agentString = stringByAddingPercentEscapesUsingEncoding(agentString);

            returnString = "?stateId=" + state.getId() + "&activityId=" + state.getActivityId().toString() + "&agent=" + agentString;
        }
        catch (Exception e)
        {
            return "";
        }

        return returnString;
    }

    /**
     * setStateWithValue - save the state to the local DB
     * @param value
     * @param stateId
     * @param activityId
     * @param agent
     * @param registration
     * @param options
     * @param stateInterface
     */
    public void setStateWithValue(Map<String,String> value, String stateId, String activityId, Agent agent, String registration, Map<String, Object> options, setStateInterface stateInterface)
    {
        State state;
        try
        {
            if (registration != null)
            {
                state = new State(stateId, value.get("bookmark"), activityId, agent, UUID.fromString(registration));
            }
            else
            {
                state = new State(stateId, value.get("bookmark"), activityId, agent);
            }
        }
        catch (Exception e)
        {
            stateInterface.errorBlock("new state " + e.toString());
            return;
        }

        Long now = Long.valueOf(System.currentTimeMillis());

        ContentValues initialValues = new ContentValues();
        try
        {
            initialValues.put(TCLocalStorageDatabase.LocalState.CREATE_DATE, now);
            initialValues.put(TCLocalStorageDatabase.LocalState.STATE_ID, stateId);
            initialValues.put(TCLocalStorageDatabase.LocalState.STATE_CONTENTS, JSONString(state));
            initialValues.put(TCLocalStorageDatabase.LocalState.QUERY_STRING, querystring(state));
            initialValues.put(TCLocalStorageDatabase.LocalState.ACTIVITY_ID, state.getActivityId().toString());
            initialValues.put(TCLocalStorageDatabase.LocalState.AGENT_JSON, agent.toJSON());

        }
        catch (Exception e)
        {
            stateInterface.errorBlock("initialValues " + e.toString());
            return;
        }

        try
        {
            appContext.getContentResolver().insert(TCLocalStorageDatabase.LocalState.CONTENT_URI, initialValues);
        }
        catch (Exception e)
        {
            stateInterface.errorBlock("insert " + e.toString());
            return;
        }

        stateInterface.completionBlock();
    }

    /**
     * enqueueStatement - save a statement to the local db
     * @param statement
     * @param addInterface
     */
    public void enqueueStatement(Statement statement, addStatementInterface addInterface)
    {
        TCOfflineStatementCollection statementQueue = new TCOfflineStatementCollection(appContext);
        statementQueue.addStatement(statement, addInterface);
    }

    //

    /**
     * get a list of local statements
     * @return List of LocalStatementsItem
     */
    public List<LocalStatementsItem> getCachedStatements()
    {
        TCOfflineStatementCollection statementQueue = new TCOfflineStatementCollection(appContext);
        return statementQueue.getCachedStatements();
    }

    public interface sendOldestInterface {
        void completionBlock();
        void errorBlock(String error);
    }

    /**
     * send the oldest statement in the local queue to the db
     * @param sendInterface
     */
    public void sendOldestStatementFromQueueWithCompletionBlock(final sendOldestInterface sendInterface)
    {

        final TCOfflineStatementCollection statementQueue = new TCOfflineStatementCollection(appContext);
        final Statement statementToSend;

        List<LocalStatementsItem> unsentStatements = statementQueue.getUnsentStatements(1);

        if(unsentStatements.size() > 0){

            LocalStatementsItem item = unsentStatements.get(0);

            try {
                statementToSend = new Statement(new StringOfJSON(item.statementJson));
                statementToSend.setId(UUID.fromString(item.statementId));

                sendStatementToServer(statementToSend,new sendStatementInterface() {
                    @Override
                    public void completionBlock() {
                        statementQueue.markStatementPosted(statementToSend);     //after we send it, mark it posted
                        sendInterface.completionBlock();
                    }

                    @Override
                    public void errorBlock(String error) {
                        sendInterface.errorBlock(error);
                    }
                });

            }
            catch (Exception e)
            {
                sendInterface.errorBlock(e.toString());
            }


        }
        else
        {
            sendInterface.errorBlock("no unsent statements");
        }



    }

    /**
     * get a list of the local state items from local db
     * @param limit
     * @return List of LocalStateItem
     */
    List<LocalStateItem> getLocalStates(int limit)
    {
        List<LocalStateItem> stateArray = new ArrayList<LocalStateItem>();

        Cursor cursor;
        SQLiteDatabase database;
        TCOfflineDataManager.TCLocalStorageDatabaseOpenHelper dbHelper;
        dbHelper = new TCOfflineDataManager.TCLocalStorageDatabaseOpenHelper(appContext);
        database = dbHelper.getWritableDatabase();
        cursor = database.query(TCOfflineDataManager.LOCAL_STATE_TABLE_NAME, null, null, null, null, null, TCLocalStorageDatabase.LocalState.CREATE_DATE + " DESC", Integer.toString(limit));      //query for all the statements

        cursor.moveToFirst();     //go to the beginning of the query and then loop through all the packages, adding them to the return List
        while (!cursor.isAfterLast()) {
            LocalStateItem thisPackage = new LocalStateItem();
            thisPackage.id = cursor.getInt(0);
            thisPackage.stateId = cursor.getString(cursor.getColumnIndex("stateId"));
            thisPackage.stateContents = cursor.getString(cursor.getColumnIndex("stateContents"));
            thisPackage.createDate = cursor.getLong(cursor.getColumnIndex("createDate"));
            thisPackage.postDate = cursor.getLong(cursor.getColumnIndex("postDate"));
            thisPackage.querystring = cursor.getString(cursor.getColumnIndex("querystring"));
            thisPackage.activityId = cursor.getString(cursor.getColumnIndex("activityId"));
            thisPackage.agentJson = cursor.getString(cursor.getColumnIndex("agentJson"));

            stateArray.add(thisPackage);
            cursor.moveToNext();
        }

        cursor.close();
        database.close();

        return stateArray;


    }

    public interface sendLocalStateInterface {
        void completionBlock();
        void errorBlock(String error);
    }

    /**
     * send local states to lrs
     * @param sendLocalInterface
     */
    public void sendLocalStateToServerWithCompletionBlock(sendLocalStateInterface sendLocalInterface)
    {
        new sendLocalStateToServerAsync().execute(sendLocalInterface);

    }

    /**
     *  send local states to lrs async
     */
    class sendLocalStateToServerAsync extends AsyncTask<Object, Integer, Boolean> {

        Exception myE = null;
        sendLocalStateInterface mySendInterface;
        @Override
        protected Boolean doInBackground(Object... info) {

            mySendInterface = (sendLocalStateInterface)info[0];
            List<LocalStateItem> stateArray = getLocalStates(50);

            RemoteLRS lrs = new RemoteLRS();
            try {
                lrs.setEndpoint(_recordStore.get(0).get("endpoint"));
                lrs.setAuth(_recordStore.get(0).get("auth"));
                lrs.setVersion(TCAPIVersion.fromString(_recordStore.get(0).get("version")));
            }
            catch (Exception e)
            {
                myE = e;
                return true;
            }

            for(int i=0; i<stateArray.size();i++)
            {
                LocalStateItem stateFromDb = stateArray.get(i);
                State state;
                try
                {
                    state = new State(stateFromDb.stateId, stateFromDb.stateContents, stateFromDb.activityId, Agent.fromJson(new StringOfJSON(stateFromDb.agentJson).toJSONNode()), null);
                }
                catch (Exception e)
                {
                    myE = e;
                    return true;
                }
                try
                {
                    lrs.saveState(state, state.getActivityId().toString(),state.getAgent(),null);
                }
                catch (Exception e)
                {
                    myE = e;
                    return true;
                }
            }

            return false;
        }

        @Override
        protected void onPostExecute(Boolean failure) {

            if (failure)
            {
                mySendInterface.errorBlock(myE.toString());
            }
            else
            {
                mySendInterface.completionBlock();
            }
        }
    }

}
