/*
 * Copyright (c) 2010-11 Dropbox, Inc.
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */


package com.mannsi.mocodroid;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.os.AsyncTask;
import android.widget.Toast;

import com.dropbox.client2.DropboxAPI;
import com.dropbox.client2.DropboxAPI.Entry;
import com.dropbox.client2.exception.DropboxException;
import com.dropbox.client2.exception.DropboxIOException;
import com.dropbox.client2.exception.DropboxParseException;
import com.dropbox.client2.exception.DropboxPartialFileException;
import com.dropbox.client2.exception.DropboxServerException;
import com.dropbox.client2.exception.DropboxUnlinkedException;

import java.util.ArrayList;

/**
 * Here we show getting metadata for a directory and downloading a file in a
 * background thread, trying to show typical exception handling and flow of
 * control for an app that downloads a file from Dropbox.
 */

public class FetchAllCommands extends AsyncTask<Void, Long, Boolean> {

    private Context mContext;
    private final ProgressDialog mDialog;
    private DropboxAPI<?> mApi;
    private String mPossibleCommandsPath;
    private String mComputersPath;

    private boolean mCanceled;
    private String mErrorMsg;
    private ArrayList<String> mPossibleCommands;
    private ArrayList<String> mComputers;
    private IUpdateAfterAsync mUpdateAfterSync;

    public FetchAllCommands(Context context, DropboxAPI<?> api,
                            String possibleCommandsPath,
                            String computersPath,
                            IUpdateAfterAsync updateAfterSync) {
        // We set the context this way so we don't accidentally leak activities
        mContext = context.getApplicationContext();

        mApi = api;
        mPossibleCommandsPath = possibleCommandsPath;
        mComputersPath = computersPath;
        mUpdateAfterSync = updateAfterSync;
        mPossibleCommands = new ArrayList<String>();
        mComputers = new ArrayList<String>();

        mDialog = new ProgressDialog(context);
        mDialog.setMessage("Fetching commands");
        mDialog.setButton(ProgressDialog.BUTTON_POSITIVE, "Cancel", new OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                mCanceled = true;
                mErrorMsg = "Canceled";
            }
        });

        mDialog.show();
    }

    @Override
    protected Boolean doInBackground(Void... params) {
        try {
            if (mCanceled) {
                return false;
            }

            // Get the metadata for a directory
            if (LoadPossibleCommands()) return false;
            if (LoadComputers()) return false;

            return !mCanceled;

        } catch (DropboxUnlinkedException e) {
            // The AuthSession wasn't properly authenticated or user unlinked.
        } catch (DropboxPartialFileException e) {
            // We canceled the operation
            mErrorMsg = "Download canceled";
        } catch (DropboxServerException e) {
            // Server-side exception.  These are examples of what could happen,
            // but we don't do anything special with them here.
            if (e.error == DropboxServerException._304_NOT_MODIFIED) {
                // won't happen since we don't pass in revision with metadata
            } else if (e.error == DropboxServerException._401_UNAUTHORIZED) {
                // Unauthorized, so we should unlink them.  You may want to
                // automatically log the user out in this case.
            } else if (e.error == DropboxServerException._403_FORBIDDEN) {
                // Not allowed to access this
            } else if (e.error == DropboxServerException._404_NOT_FOUND) {
                // path not found (or if it was the thumbnail, can't be
                // thumbnailed)
            } else if (e.error == DropboxServerException._406_NOT_ACCEPTABLE) {
                // too many entries to return
            } else if (e.error == DropboxServerException._415_UNSUPPORTED_MEDIA) {
                // can't be thumbnailed
            } else if (e.error == DropboxServerException._507_INSUFFICIENT_STORAGE) {
                // user is over quota
            } else {
                // Something else
            }
            // This gets the Dropbox error, translated into the user's language
            mErrorMsg = e.body.userError;
            if (mErrorMsg == null) {
                mErrorMsg = e.body.error;
            }
        } catch (DropboxIOException e) {
            // Happens all the time, probably want to retry automatically.
            mErrorMsg = "Network error.  Try again.";
        } catch (DropboxParseException e) {
            // Probably due to Dropbox server restarting, should retry
            mErrorMsg = "Dropbox error.  Try again.";
        } catch (DropboxException e) {
            // Unknown error
            mErrorMsg = "Unknown error.  Try again.";
        }
        return false;
    }

    private boolean LoadPossibleCommands() throws DropboxException {
        Entry commandsDirectory = mApi.metadata(mPossibleCommandsPath, 1000, null, true, null);

        if (!commandsDirectory.isDir || commandsDirectory.contents == null) {
            mErrorMsg = "No command found";
            return true;
        }

        for (Entry ent: commandsDirectory.contents) {
            String fileName = ent.path.replace(mPossibleCommandsPath, "");
            mPossibleCommands.add(fileName);
        }
        return false;
    }

    private boolean LoadComputers() throws DropboxException {
        Entry computersDirectory = mApi.metadata(mComputersPath, 1000, null, true, null);

        if (!computersDirectory.isDir || computersDirectory.contents == null) {
            mErrorMsg = "No computers found";
            return true;
        }

        for (Entry ent: computersDirectory.contents) {
            if (ent.isDir)
            {
                String fileName = ent.path.replace(mComputersPath, "");
                mComputers.add(fileName);
            }

        }
        return false;
    }
    @Override
    protected void onProgressUpdate(Long... progress) {

    }

    @Override
    protected void onPostExecute(Boolean result) {
        mDialog.dismiss();
        if (result) {
            // Set the image now that we have it
            //mView.setImageDrawable(mDrawable);
            mUpdateAfterSync.Update();
        } else {
            // Couldn't download it, so show an error
            showToast(mErrorMsg);
        }
    }

    private void showToast(String msg) {
        Toast error = Toast.makeText(mContext, msg, Toast.LENGTH_LONG);
        error.show();
    }

    public ArrayList<String> GetAllCommands()
    {
        return mPossibleCommands;
    }
    public ArrayList<String> GetComputers() {return mComputers; }
}
