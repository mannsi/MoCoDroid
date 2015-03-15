package com.mannsi.mocodroid;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.ExpandableListView;
import android.widget.LinearLayout;
import android.widget.SimpleExpandableListAdapter;
import android.widget.Toast;

import com.dropbox.client2.DropboxAPI;
import com.dropbox.client2.android.AndroidAuthSession;
import com.dropbox.client2.android.AuthActivity;
import com.dropbox.client2.exception.DropboxException;
import com.dropbox.client2.session.AccessTokenPair;
import com.dropbox.client2.session.AppKeyPair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MoCo extends Activity implements IUpdateAfterAsync{
    private static final String TAG = "MoCo";

    private static String APP_KEY;
    private static String APP_SECRET;

    private static final String ACCOUNT_PREFS_NAME = "prefs";
    private static final String ACCESS_KEY_NAME = "ACCESS_KEY";
    private static final String ACCESS_SECRET_NAME = "ACCESS_SECRET";

    private static final boolean USE_OAUTH1 = false;
    DropboxAPI<AndroidAuthSession> mApi;
    private boolean mLoggedIn;

    // Android widgets
    private Button mLinkDropbox;

    FetchAllCommands mFetchAllCommands;
    SimpleExpandableListAdapter mSimpleExpandableListAdapter;
    private String mAllCommandsFolder = "/PossibleCommands/";
    private String mComputersFolder = "/CommandsToRun/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // TODO figure out if this is returning the right stuff. Also if AndroidManifest.xml is getting the correct data from the keys xml
        APP_KEY = getString(R.string.APP_KEY);
        APP_SECRET = getString(R.string.APP_SECRET);

        // We create a new AuthSession so that we can use the Dropbox API.
        AndroidAuthSession session = buildSession();
        mApi = new DropboxAPI<AndroidAuthSession>(session);

        setContentView(R.layout.activity_fullscreen);
        checkAppKeySetup();

        mLinkDropbox = (Button)findViewById(R.id.link_button);

        mLinkDropbox.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                // This logs you out if you're logged in, or vice versa
                if (mLoggedIn) {
                    logOut();
                } else {
                    // Start the remote authentication
                    if (USE_OAUTH1) {
                        mApi.getSession().startAuthentication(MoCo.this);
                    } else {
                        mApi.getSession().startOAuth2Authentication(MoCo.this);
                    }
                }
            }
        });

        Button mRefreshCommands = (Button) findViewById(R.id.refresh_commands);
        mRefreshCommands.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                FetchCommandsAndComputers();
            }
        });

        // Display the proper UI state if logged in or not
        boolean loggedIn = mApi.getSession().isLinked();
        setLoggedIn(loggedIn);

        if (loggedIn) {
            FetchCommandsAndComputers();
        }
    }

    private void FetchCommandsAndComputers()
    {
        mFetchAllCommands = new FetchAllCommands(MoCo.this, mApi, mAllCommandsFolder, mComputersFolder, this);
        mFetchAllCommands.execute();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AndroidAuthSession session = mApi.getSession();

        // The next part must be inserted in the onResume() method of the
        // activity from which session.startAuthentication() was called, so
        // that Dropbox authentication completes properly.
        if (session.authenticationSuccessful()) {
            try {
                // Mandatory call to complete the auth
                session.finishAuthentication();

                // Store it locally in our app for later use
                storeAuth(session);
                setLoggedIn(true);
            } catch (IllegalStateException e) {
                showToast("Couldn't authenticate with Dropbox:" + e.getLocalizedMessage());
                Log.i(TAG, "Error authenticating", e);
            }
        }
    }

    private void logOut() {
        // Remove credentials from the session
        mApi.getSession().unlink();

        // Clear our stored keys
        clearKeys();
        // Change UI state to display logged out version
        setLoggedIn(false);
    }

    /**
     * Convenience function to change UI state based on being logged in
     */
    private void setLoggedIn(boolean loggedIn) {
        mLoggedIn = loggedIn;
        LinearLayout mDisplay = (LinearLayout)findViewById(R.id.logged_in_display);
        if (loggedIn) {
            mDisplay.setVisibility(View.VISIBLE);
            mLinkDropbox.setVisibility(View.INVISIBLE);
        } else {
            mLinkDropbox.setText("Link with Dropbox");
            mLinkDropbox.setEnabled(true);
            mDisplay.setVisibility(View.GONE);
        }
    }

    private void checkAppKeySetup() {
        // Check if the app has set up its manifest properly.
        Intent testIntent = new Intent(Intent.ACTION_VIEW);
        String scheme = "db-" + APP_KEY;
        String uri = scheme + "://" + AuthActivity.AUTH_VERSION + "/test";
        testIntent.setData(Uri.parse(uri));
        PackageManager pm = getPackageManager();
        if (0 == pm.queryIntentActivities(testIntent, 0).size()) {
            showToast("URL scheme in your app's " +
                    "manifest is not set up correctly. You should have a " +
                    "com.dropbox.client2.android.AuthActivity with the " +
                    "scheme: " + scheme);
            finish();
        }
    }

    private void showToast(String msg) {
        Toast error = Toast.makeText(this, msg, Toast.LENGTH_LONG);
        error.show();
    }

    /**
     * Shows keeping the access keys returned from Trusted Authenticator in a local
     * store, rather than storing user name & password, and re-authenticating each
     * time (which is not to be done, ever).
     */
    private void loadAuth(AndroidAuthSession session) {
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
        String key = prefs.getString(ACCESS_KEY_NAME, null);
        String secret = prefs.getString(ACCESS_SECRET_NAME, null);
        if (key == null || secret == null || key.length() == 0 || secret.length() == 0) return;

        if (key.equals("oauth2:")) {
            // If the key is set to "oauth2:", then we can assume the token is for OAuth 2.
            session.setOAuth2AccessToken(secret);
        } else {
            // Still support using old OAuth 1 tokens.
            session.setAccessTokenPair(new AccessTokenPair(key, secret));
        }
    }

    /**
     * Shows keeping the access keys returned from Trusted Authenticator in a local
     * store, rather than storing user name & password, and re-authenticating each
     * time (which is not to be done, ever).
     */
    private void storeAuth(AndroidAuthSession session) {
        // Store the OAuth 2 access token, if there is one.
        String oauth2AccessToken = session.getOAuth2AccessToken();
        if (oauth2AccessToken != null) {
            SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
            Editor edit = prefs.edit();
            edit.putString(ACCESS_KEY_NAME, "oauth2:");
            edit.putString(ACCESS_SECRET_NAME, oauth2AccessToken);
            edit.commit();
            return;
        }
        // Store the OAuth 1 access token, if there is one.  This is only necessary if
        // you're still using OAuth 1.
        AccessTokenPair oauth1AccessToken = session.getAccessTokenPair();
        if (oauth1AccessToken != null) {
            SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
            Editor edit = prefs.edit();
            edit.putString(ACCESS_KEY_NAME, oauth1AccessToken.key);
            edit.putString(ACCESS_SECRET_NAME, oauth1AccessToken.secret);
            edit.commit();
        }
    }

    private void clearKeys() {
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
        Editor edit = prefs.edit();
        edit.clear();
        edit.commit();
    }

    private AndroidAuthSession buildSession() {
        AppKeyPair appKeyPair = new AppKeyPair(APP_KEY, APP_SECRET);

        AndroidAuthSession session = new AndroidAuthSession(appKeyPair);
        loadAuth(session);
        return session;
    }

    public void Update()
    {
        ArrayList<String> allCommands = mFetchAllCommands.GetAllCommands();
        List<Map<String, String>> groupData = new ArrayList<Map<String, String>>();
        List<List<Map<String, String>>> listOfChildGroups = new ArrayList<List<Map<String, String>>>();
        for (final String command: allCommands)
        {
            groupData.add(new HashMap<String, String>() {{
                put("ROOT_NAME", command);
            }});

            List<Map<String, String>> computerSubList = new ArrayList<Map<String, String>>();
            ArrayList<String> computers = mFetchAllCommands.GetComputers();
            for (final String computer: computers)
            {
                computerSubList.add(new HashMap<String, String>() {{
                    put("CHILD_NAME", computer);
                }});
            }
            listOfChildGroups.add(computerSubList);
        }

        mSimpleExpandableListAdapter =  new SimpleExpandableListAdapter(
                this,

                groupData,
                android.R.layout.simple_expandable_list_item_1,
                new String[] { "ROOT_NAME" },
                new int[] { android.R.id.text1 },

                listOfChildGroups,
                android.R.layout.simple_expandable_list_item_2,
                new String[] { "CHILD_NAME" },
                new int[] { android.R.id.text1 }
        );

        ExpandableListView elv = (ExpandableListView)findViewById(R.id.expandableListView);
        elv.setOnChildClickListener(new ExpandableListView.OnChildClickListener() {
            public boolean onChildClick(ExpandableListView expandableListView, View view, int i, int i2, long l) {
                View v = view;
                ExpandableListView e = expandableListView;
                Map<String, String> map = (Map<String, String>)(mSimpleExpandableListAdapter.getGroup(i));
                String selectedFile = map.get("ROOT_NAME");

                Map<String, String> map2 = (Map<String, String>)(mSimpleExpandableListAdapter.getChild(i, i2));
                String selectedComputer = map2.get("CHILD_NAME");

                try {
                    mApi.copy(mAllCommandsFolder + selectedFile, mComputersFolder + selectedComputer + "/" + selectedFile);
                } catch (DropboxException ex) {
                    ex.printStackTrace();
                }

                return false;
            }
        });
        elv.setAdapter(mSimpleExpandableListAdapter);
    }
}
