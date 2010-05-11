/*
 * Copyright (C) 2010 Thialfihar <thi@thialfihar.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.thialfihar.android.apg;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Security;
import java.security.SignatureException;
import java.util.regex.Matcher;

import org.bouncycastle2.jce.provider.BouncyCastleProvider;
import org.bouncycastle2.openpgp.PGPException;
import org.bouncycastle2.util.Strings;
import org.openintents.intents.FileManager;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.text.ClipboardManager;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

public class DecryptActivity extends BaseActivity {
    private long mSignatureKeyId = 0;

    private String mReplyTo = null;
    private String mSubject = null;
    private boolean mSignedOnly = false;
    private boolean mAssumeSymmetricEncryption = false;

    private EditText mMessage = null;
    private LinearLayout mSignatureLayout = null;
    private ImageView mSignatureStatusImage = null;
    private TextView mUserId = null;
    private TextView mUserIdRest = null;

    private ViewFlipper mSource = null;
    private TextView mSourceLabel = null;
    private ImageView mSourcePrevious = null;
    private ImageView mSourceNext = null;

    private Button mDecryptButton = null;
    private Button mReplyButton = null;

    private int mDecryptTarget;

    private EditText mFilename = null;
    private CheckBox mDeleteAfter = null;
    private ImageButton mBrowse = null;

    private String mInputFilename = null;
    private String mOutputFilename = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.decrypt);

        mSource = (ViewFlipper) findViewById(R.id.source);
        mSourceLabel = (TextView) findViewById(R.id.source_label);
        mSourcePrevious = (ImageView) findViewById(R.id.source_previous);
        mSourceNext = (ImageView) findViewById(R.id.source_next);

        mSourcePrevious.setClickable(true);
        mSourcePrevious.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mSource.setInAnimation(AnimationUtils.loadAnimation(DecryptActivity.this,
                                                                    R.anim.push_right_in));
                mSource.setOutAnimation(AnimationUtils.loadAnimation(DecryptActivity.this,
                                                                     R.anim.push_right_out));
                mSource.showPrevious();
                updateSource();
            }
        });

        mSourceNext.setClickable(true);
        OnClickListener nextSourceClickListener = new OnClickListener() {
            @Override
            public void onClick(View v) {
                mSource.setInAnimation(AnimationUtils.loadAnimation(DecryptActivity.this,
                                                                    R.anim.push_left_in));
                mSource.setOutAnimation(AnimationUtils.loadAnimation(DecryptActivity.this,
                                                                     R.anim.push_left_out));
                mSource.showNext();
                updateSource();
            }
        };
        mSourceNext.setOnClickListener(nextSourceClickListener);

        mSourceLabel.setClickable(true);
        mSourceLabel.setOnClickListener(nextSourceClickListener);

        mMessage = (EditText) findViewById(R.id.message);
        mDecryptButton = (Button) findViewById(R.id.btn_decrypt);
        mReplyButton = (Button) findViewById(R.id.btn_reply);
        mSignatureLayout = (LinearLayout) findViewById(R.id.layout_signature);
        mSignatureStatusImage = (ImageView) findViewById(R.id.ic_signature_status);
        mUserId = (TextView) findViewById(R.id.main_user_id);
        mUserIdRest = (TextView) findViewById(R.id.main_user_id_rest);

        // measure the height of the source_file view and set the message view's min height to that,
        // so it fills mSource fully... bit of a hack.
        View tmp = findViewById(R.id.source_file);
        tmp.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int height = tmp.getMeasuredHeight();
        mMessage.setMinimumHeight(height);

        mFilename = (EditText) findViewById(R.id.filename);
        mBrowse = (ImageButton) findViewById(R.id.btn_browse);
        mBrowse.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFile();
            }
        });

        mDeleteAfter = (CheckBox) findViewById(R.id.delete_after_decryption);

        // default: message source
        mSource.setInAnimation(null);
        mSource.setOutAnimation(null);
        while (mSource.getCurrentView().getId() != R.id.source_message) {
            mSource.showNext();
        }

        Intent intent = getIntent();
        if (intent.getAction() != null && intent.getAction().equals(Intent.ACTION_VIEW)) {
            Uri uri = intent.getData();
            try {
                InputStream attachment = getContentResolver().openInputStream(uri);
                ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
                byte bytes[] = new byte[1 << 16];
                int length;
                while ((length = attachment.read(bytes)) > 0) {
                    byteOut.write(bytes, 0, length);
                }
                byteOut.close();
                String data = Strings.fromUTF8ByteArray(byteOut.toByteArray());
                mMessage.setText(data);
            } catch (FileNotFoundException e) {
                // ignore, then
            } catch (IOException e) {
                // ignore, then
            }
        } else if (intent.getAction() != null && intent.getAction().equals(Apg.Intent.DECRYPT)) {
            Bundle extras = intent.getExtras();
            if (extras == null) {
                extras = new Bundle();
            }
            String data = extras.getString("data");
            if (data != null) {
                Matcher matcher = Apg.PGP_MESSAGE.matcher(data);
                if (matcher.matches()) {
                    data = matcher.group(1);
                    // replace non breakable spaces
                    data = data.replaceAll("\\xa0", " ");
                    mMessage.setText(data);
                } else {
                    matcher = Apg.PGP_SIGNED_MESSAGE.matcher(data);
                    if (matcher.matches()) {
                        data = matcher.group(1);
                        // replace non breakable spaces
                        data = data.replaceAll("\\xa0", " ");
                        mMessage.setText(data);
                        mDecryptButton.setText(R.string.btn_verify);
                    }
                }
            }
            mReplyTo = extras.getString("replyTo");
            mSubject = extras.getString("subject");
        } else if (intent.getAction() != null && intent.getAction().equals(Apg.Intent.DECRYPT_FILE)) {
            mSource.setInAnimation(null);
            mSource.setOutAnimation(null);
            while (mSource.getCurrentView().getId() != R.id.source_file) {
                mSource.showNext();
            }
        }

        Log.e("err?", "" + mSource.getCurrentView().getId() + " " + R.id.source_message + " " + mMessage.getText().length());
        if (mSource.getCurrentView().getId() == R.id.source_message &&
            mMessage.getText().length() == 0) {
            ClipboardManager clip = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            String data = "";
            Matcher matcher = Apg.PGP_MESSAGE.matcher(clip.getText());
            if (!matcher.matches()) {
                matcher = Apg.PGP_SIGNED_MESSAGE.matcher(clip.getText());
            }
            if (matcher.matches()) {
                data = matcher.group(1);
                mMessage.setText(data);
                Toast.makeText(this, R.string.using_clipboard_content, Toast.LENGTH_SHORT).show();
            }
        }

        mSignatureLayout.setVisibility(View.GONE);

        mDecryptButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                decryptClicked();
            }
        });

        mReplyButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                replyClicked();
            }
        });
        mReplyButton.setVisibility(View.INVISIBLE);

        if (mSource.getCurrentView().getId() == R.id.source_message &&
            mMessage.getText().length() > 0) {
            mDecryptButton.performClick();
        }

        updateSource();
    }

    private void openFile() {
        String filename = mFilename.getText().toString();

        Intent intent = new Intent(FileManager.ACTION_PICK_FILE);

        intent.setData(Uri.parse("file://" + filename));

        intent.putExtra(FileManager.EXTRA_TITLE, "Select file to decrypt...");
        intent.putExtra(FileManager.EXTRA_BUTTON_TEXT, "Open");

        try {
            startActivityForResult(intent, Id.request.filename);
        } catch (ActivityNotFoundException e) {
            // No compatible file manager was found.
            Toast.makeText(this, R.string.no_filemanager_installed, Toast.LENGTH_SHORT).show();
        }
    }

    private void guessOutputFilename() {
        mInputFilename = mFilename.getText().toString();
        File file = new File(mInputFilename);
        String filename = file.getName();
        if (filename.endsWith(".asc") || filename.endsWith(".gpg")) {
            filename = filename.substring(0, filename.length() - 4);
        }
        mOutputFilename = Constants.path.app_dir + "/" + filename;
    }

    private void updateSource() {
        switch (mSource.getCurrentView().getId()) {
            case R.id.source_file: {
                mSourceLabel.setText(R.string.label_file);
                mDecryptButton.setText(R.string.btn_decrypt);
                break;
            }

            case R.id.source_message: {
                mSourceLabel.setText(R.string.label_message);
                mDecryptButton.setText(R.string.btn_decrypt);
                break;
            }

            default: {
                break;
            }
        }
    }

    private void decryptClicked() {
        if (mSource.getCurrentView().getId() == R.id.source_file) {
            mDecryptTarget = Id.target.file;
        } else {
            mDecryptTarget = Id.target.message;
        }
        initiateDecryption();
    }

    private void initiateDecryption() {
        if (mDecryptTarget == Id.target.file) {
            String currentFilename = mFilename.getText().toString();
            if (mInputFilename == null || !mInputFilename.equals(currentFilename)) {
                guessOutputFilename();
            }

            if (mInputFilename.equals("")) {
                Toast.makeText(this, "Select a file first.", Toast.LENGTH_SHORT).show();
                return;
            }

            File file = new File(mInputFilename);
            if (!file.exists() || !file.isFile()) {
                Toast.makeText(this, "Error: file not found", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        if (mDecryptTarget == Id.target.message) {
            String messageData = mMessage.getText().toString();
            Matcher matcher = Apg.PGP_SIGNED_MESSAGE.matcher(messageData);
            if (matcher.matches()) {
                mSignedOnly = true;
                decryptStart();
                return;
            }
        }

        // else treat it as an decrypted message/file
        mSignedOnly = false;
        String error = null;
        try {
            InputStream in;
            if (mDecryptTarget == Id.target.file) {
                in = new FileInputStream(mInputFilename);
            } else {
                in = new ByteArrayInputStream(mMessage.getText().toString().getBytes());
            }
            try {
                setSecretKeyId(Apg.getDecryptionKeyId(in));
                if (getSecretKeyId() == 0) {
                    throw new Apg.GeneralException("no suitable secret key found");
                }
                mAssumeSymmetricEncryption = false;
            } catch (Apg.NoAsymmetricEncryptionException e) {
                setSecretKeyId(0);
                // reopen the file/message to check whether there's
                // symmetric encryption data in there
                if (mDecryptTarget == Id.target.file) {
                    in = new FileInputStream(mInputFilename);
                } else {
                    in = new ByteArrayInputStream(mMessage.getText().toString().getBytes());
                }
                if (!Apg.hasSymmetricEncryption(in)) {
                    throw new Apg.GeneralException("no known kind of encryption found");
                }
                mAssumeSymmetricEncryption = true;
           }

           showDialog(Id.dialog.pass_phrase);
        } catch (FileNotFoundException e) {
            error = "file not found: " + e.getLocalizedMessage();
        } catch (IOException e) {
            error = e.getLocalizedMessage();
        } catch (Apg.GeneralException e) {
            error = e.getLocalizedMessage();
        }
        if (error != null) {
            Toast.makeText(this, "Error: " + error, Toast.LENGTH_SHORT).show();
        }
    }

    private void replyClicked() {
        Intent intent = new Intent(this, EncryptActivity.class);
        intent.setAction(Apg.Intent.ENCRYPT);
        String data = mMessage.getText().toString();
        data = data.replaceAll("(?m)^", "> ");
        data = "\n\n" + data;
        intent.putExtra("data", data);
        intent.putExtra("subject", "Re: " + mSubject);
        intent.putExtra("sendTo", mReplyTo);
        intent.putExtra("eyId", mSignatureKeyId);
        intent.putExtra("signatureKeyId", getSecretKeyId());
        intent.putExtra("encryptionKeyIds", new long[] { mSignatureKeyId });
        startActivity(intent);
    }

    private void askForOutputFilename() {
        showDialog(Id.dialog.output_filename);
    }

    @Override
    public void passPhraseCallback(String passPhrase) {
        super.passPhraseCallback(passPhrase);
        if (mDecryptTarget == Id.target.file) {
            askForOutputFilename();
        } else {
            decryptStart();
        }
    }

    private void decryptStart() {
        showDialog(Id.dialog.decrypting);
        startThread();
    }

    @Override
    public void run() {
        String error = null;
        Security.addProvider(new BouncyCastleProvider());

        Bundle data = new Bundle();
        Message msg = new Message();

        try {
            InputStream in = null;
            OutputStream out = null;
            if (mDecryptTarget == Id.target.message) {
                String messageData = mMessage.getText().toString();
                in = new ByteArrayInputStream(messageData.getBytes());
                out = new ByteArrayOutputStream();
            } else {
                in = new FileInputStream(mInputFilename);
                out = new FileOutputStream(mOutputFilename);
            }

            if (mSignedOnly) {
                data = Apg.verifyText(in, out, this);
            } else {
                data = Apg.decrypt(in, out, Apg.getPassPhrase(), this, mAssumeSymmetricEncryption);
            }

            out.close();
            if (mDecryptTarget == Id.target.message) {
                data.putString("decryptedMessage",
                               Strings.fromUTF8ByteArray(((ByteArrayOutputStream)
                                                              out).toByteArray()));
            }
        } catch (PGPException e) {
            error = e.getMessage();
        } catch (IOException e) {
            error = e.getMessage();
        } catch (SignatureException e) {
            error = e.getMessage();
            e.printStackTrace();
        } catch (Apg.GeneralException e) {
            error = e.getMessage();
        }

        data.putInt("type", Id.message.done);

        if (error != null) {
            data.putString("error", error);
        }

        msg.setData(data);
        sendMessage(msg);
    }

    @Override
    public void doneCallback(Message msg) {
        super.doneCallback(msg);

        Bundle data = msg.getData();
        removeDialog(Id.dialog.decrypting);
        mSignatureKeyId = 0;
        mSignatureLayout.setVisibility(View.GONE);
        mReplyButton.setVisibility(View.INVISIBLE);

        String error = data.getString("error");
        if (error != null) {
            Toast.makeText(DecryptActivity.this,
                           "Error: " + data.getString("error"),
                           Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Successfully decrypted.", Toast.LENGTH_SHORT).show();
        switch (mDecryptTarget) {
            case Id.target.message: {
                String decryptedMessage = data.getString("decryptedMessage");
                mMessage.setText(decryptedMessage);
                mReplyButton.setVisibility(View.VISIBLE);
                break;
            }

            case Id.target.file: {
                if (mDeleteAfter.isChecked()) {
                    setDeleteFile(mInputFilename);
                    showDialog(Id.dialog.delete_file);
                }
                break;
            }

            default: {
                // shouldn't happen
                break;
            }
        }

        if (data.getBoolean("signature")) {
            String userId = data.getString("signatureUserId");
            mSignatureKeyId = data.getLong("signatureKeyId");
            mUserIdRest.setText("id: " + Long.toHexString(mSignatureKeyId & 0xffffffffL));
            if (userId == null) {
                userId = getResources().getString(R.string.unknown_user_id);
            }
            String chunks[] = userId.split(" <", 2);
            userId = chunks[0];
            if (chunks.length > 1) {
                mUserIdRest.setText("<" + chunks[1]);
            }
            mUserId.setText(userId);

            if (data.getBoolean("signatureSuccess")) {
                mSignatureStatusImage.setImageResource(R.drawable.overlay_ok);
            } else if (data.getBoolean("signatureUnknown")) {
                mSignatureStatusImage.setImageResource(R.drawable.overlay_error);
            } else {
                mSignatureStatusImage.setImageResource(R.drawable.overlay_error);
            }
            mSignatureLayout.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case Id.request.filename: {
                if (resultCode == RESULT_OK && data != null) {
                    String filename = data.getDataString();
                    if (filename != null) {
                        // Get rid of URI prefix:
                        if (filename.startsWith("file://")) {
                            filename = filename.substring(7);
                        }
                        // replace %20 and so on
                        filename = Uri.decode(filename);

                        mFilename.setText(filename);
                    }
                }
                return;
            }

            case Id.request.output_filename: {
                if (resultCode == RESULT_OK && data != null) {
                    String filename = data.getDataString();
                    if (filename != null) {
                        // Get rid of URI prefix:
                        if (filename.startsWith("file://")) {
                            filename = filename.substring(7);
                        }
                        // replace %20 and so on
                        filename = Uri.decode(filename);

                        FileDialog.setFilename(filename);
                    }
                }
                return;
            }

            default: {
                break;
            }
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
            case Id.dialog.output_filename: {
                return FileDialog.build(this, "Decrypt to file",
                                        "Please specify which file to decrypt to.\n" +
                                        "WARNING! File will be overwritten if it exists.",
                                        mOutputFilename,
                                        new FileDialog.OnClickListener() {

                                            @Override
                                            public void onOkClick(String filename) {
                                                removeDialog(Id.dialog.output_filename);
                                                mOutputFilename = filename;
                                                decryptStart();
                                            }

                                            @Override
                                            public void onCancelClick() {
                                                removeDialog(Id.dialog.output_filename);
                                            }
                                        },
                                        getString(R.string.filemanager_title_save),
                                        getString(R.string.filemanager_btn_save),
                                        Id.request.output_filename);
            }

            default: {
                break;
            }
        }

        return super.onCreateDialog(id);
    }
}