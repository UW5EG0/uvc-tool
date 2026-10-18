package ua.radiomaniac.uvctool;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;
import android.widget.Toast;

/** PackageInstaller session status: shows the system confirmation, reports failures. */
public class UpdateReceiver extends BroadcastReceiver {

    private static final String TAG = "UvcTool";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Log.i(TAG, "install status=" + status + " msg=" + msg);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(confirm);
                }
                break;
            }
            case PackageInstaller.STATUS_SUCCESS:
                // normally not seen: the process is replaced by the new version
                break;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                Toast.makeText(ctx, R.string.update_cancelled, Toast.LENGTH_SHORT).show();
                break;
            default:
                Toast.makeText(ctx, ctx.getString(R.string.update_failed, msg != null ? msg : ("" + status)),
                        Toast.LENGTH_LONG).show();
        }
    }
}
