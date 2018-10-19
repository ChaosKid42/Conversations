package eu.siacs.conversations.services;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.preference.PreferenceManager;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.services.XmppConnectionService;

public class EventReceiver extends BroadcastReceiver {

    public static final String SETTING_ENABLED_ACCOUNTS = "enabled_accounts";
    public static final String EXTRA_NEEDS_FOREGROUND_SERVICE = "needs_foreground_service";

    @Override
    public void onReceive(final Context context, final Intent originalIntent) {
        final Intent intentForService = new Intent(context, XmppConnectionService.class);
        if (originalIntent.getAction() != null) {
            intentForService.setAction(originalIntent.getAction());
        } else {
            intentForService.setAction("other");
        }
        final String action = originalIntent.getAction();
        if (action.equals("ui") || hasEnabledAccounts(context)
            || action.equals(XmppConnectionService.ACTION_CLEAR_APP_DATA)) {
            Compatibility.startService(context, intentForService);
            if (Config.PROCESS_CLEAR_APP_DATA_ACTION
                && action.equals(XmppConnectionService.ACTION_CLEAR_APP_DATA)) {
                setResultCode(Activity.RESULT_OK);
            }
        } else {
            Log.d(Config.LOGTAG, "EventReceiver ignored action " + intentForService.getAction());
        }
    }

    public static boolean hasEnabledAccounts(final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(SETTING_ENABLED_ACCOUNTS, true);
    }

}
