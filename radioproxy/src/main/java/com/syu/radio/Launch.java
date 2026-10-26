package com.syu.radio;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** Opens Car Radio and gets out of the way. No UI, no permissions, no background work. */
public class Launch extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent i = getPackageManager().getLaunchIntentForPackage("com.nik.carradio");
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            i.putExtra("from_radio_key", true);
            startActivity(i);
        } else {
            Toast.makeText(this, "Install Car Radio first (com.nik.carradio)", Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
