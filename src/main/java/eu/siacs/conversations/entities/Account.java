package eu.siacs.conversations.entities;

import android.content.ContentValues;
import android.database.Cursor;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.PgpDecryptionService;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.axolotl.XmppAxolotlSession;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.XmppUri;
import eu.siacs.conversations.xmpp.XmppConnection;
import rocks.xmpp.addr.Jid;

public class Account extends AbstractEntity {

    public static final String TABLENAME = "accounts";

    public static final String USERNAME = "username";
    public static final String SERVER = "server";
    public static final String PASSWORD = "password";
    public static final String OPTIONS = "options";
    public static final String ROSTERVERSION = "rosterversion";
    public static final String KEYS = "keys";
    public static final String AVATAR = "avatar";
    public static final String DISPLAY_NAME = "display_name";
    public static final String HOSTNAME = "hostname";
    public static final String PORT = "port";
    public static final String STATUS = "status";
    public static final String STATUS_MESSAGE = "status_message";
    public static final String RESOURCE = "resource";

    public static final String PINNED_MECHANISM_KEY = "pinned_mechanism";

    public static final int OPTION_USETLS = 0;
    public static final int OPTION_DISABLED = 1;
    public static final int OPTION_REGISTER = 2;
    public static final int OPTION_USECOMPRESSION = 3;
    public static final int OPTION_MAGIC_CREATE = 4;
    public static final int OPTION_REQUIRES_ACCESS_MODE_CHANGE = 5;
    public static final int OPTION_LOGGED_IN_SUCCESSFULLY = 6;
    public static final int OPTION_HTTP_UPLOAD_AVAILABLE = 7;
    public static final int OPTION_UNVERIFIED = 8;
    private static final String KEY_PGP_SIGNATURE = "pgp_signature";
    private static final String KEY_PGP_ID = "pgp_id";
    public final HashSet<Pair<String, String>> inProgressDiscoFetches = new HashSet<>();
    protected final JSONObject keys;
    private final Roster roster = new Roster(this);
    private final Collection<Jid> blocklist = new CopyOnWriteArraySet<>();
    public List<Conversation> pendingConferenceJoins = new CopyOnWriteArrayList<>();
    public List<Conversation> pendingConferenceLeaves = new CopyOnWriteArrayList<>();
    protected Jid jid;
    protected String password;
    protected int options = 0;
    protected State status = State.OFFLINE;
    private State lastErrorStatus = State.OFFLINE;
    protected String resource;
    protected String avatar;
    protected String hostname = null;
    protected int port = 5222;
    protected boolean online = false;
    private String rosterVersion;
    private String displayName = null;
    private AxolotlService axolotlService = null;
    private PgpDecryptionService pgpDecryptionService = null;
    private XmppConnection xmppConnection = null;
    private long mEndGracePeriod = 0L;
    private List<Bookmark> bookmarks = new CopyOnWriteArrayList<>();
    private Presence.Status presenceStatus = Presence.Status.ONLINE;
    private String presenceStatusMessage = null;

    public Account(final Jid jid, final String password) {
        this(java.util.UUID.randomUUID().toString(), jid,
                password, 0, null, "", null, null, null, 5222, Presence.Status.ONLINE, null);
    }

    private Account(final String uuid, final Jid jid,
                    final String password, final int options, final String rosterVersion, final String keys,
                    final String avatar, String displayName, String hostname, int port,
                    final Presence.Status status, String statusMessage) {
        this.uuid = uuid;
        this.jid = jid;
        this.password = password;
        this.options = options;
        this.rosterVersion = rosterVersion;
        JSONObject tmp;
        try {
            tmp = new JSONObject(keys);
        } catch (JSONException e) {
            tmp = new JSONObject();
        }
        this.keys = tmp;
        this.avatar = avatar;
        this.displayName = displayName;
        this.hostname = hostname;
        this.port = port;
        this.presenceStatus = status;
        this.presenceStatusMessage = statusMessage;
    }

    public static Account fromCursor(final Cursor cursor) {
        final Jid jid;
        try {
            String resource = cursor.getString(cursor.getColumnIndex(RESOURCE));
            jid = Jid.of(
                    cursor.getString(cursor.getColumnIndex(USERNAME)),
                    cursor.getString(cursor.getColumnIndex(SERVER)),
                    resource == null || resource.trim().isEmpty() ? null : resource);
        } catch (final IllegalArgumentException ignored) {
            Log.d(Config.LOGTAG, cursor.getString(cursor.getColumnIndex(USERNAME)) + "@" + cursor.getString(cursor.getColumnIndex(SERVER)));
            throw new AssertionError(ignored);
        }
        return new Account(cursor.getString(cursor.getColumnIndex(UUID)),
                jid,
                cursor.getString(cursor.getColumnIndex(PASSWORD)),
                cursor.getInt(cursor.getColumnIndex(OPTIONS)),
                cursor.getString(cursor.getColumnIndex(ROSTERVERSION)),
                cursor.getString(cursor.getColumnIndex(KEYS)),
                cursor.getString(cursor.getColumnIndex(AVATAR)),
                cursor.getString(cursor.getColumnIndex(DISPLAY_NAME)),
                cursor.getString(cursor.getColumnIndex(HOSTNAME)),
                cursor.getInt(cursor.getColumnIndex(PORT)),
                Presence.Status.fromShowString(cursor.getString(cursor.getColumnIndex(STATUS))),
                cursor.getString(cursor.getColumnIndex(STATUS_MESSAGE)));
    }

    public boolean httpUploadAvailable(long filesize) {
        return xmppConnection != null && (xmppConnection.getFeatures().httpUpload(filesize) || xmppConnection.getFeatures().p1S3FileTransfer());
    }

    public boolean httpUploadAvailable() {
        return isOptionSet(OPTION_HTTP_UPLOAD_AVAILABLE) || httpUploadAvailable(0);
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public XmppConnection.Identity getServerIdentity() {
        if (xmppConnection == null) {
            return XmppConnection.Identity.UNKNOWN;
        } else {
            return xmppConnection.getServerIdentity();
        }
    }

    public Contact getSelfContact() {
        return getRoster().getContact(jid);
    }

    public boolean hasPendingPgpIntent(Conversation conversation) {
        return pgpDecryptionService != null && pgpDecryptionService.hasPendingIntent(conversation);
    }

    public boolean isPgpDecryptionServiceConnected() {
        return pgpDecryptionService != null && pgpDecryptionService.isConnected();
    }

    public boolean setShowErrorNotification(boolean newValue) {
        boolean oldValue = showErrorNotification();
        setKey("show_error", Boolean.toString(newValue));
        return newValue != oldValue;
    }

    public boolean showErrorNotification() {
        String key = getKey("show_error");
        return key == null || Boolean.parseBoolean(key);
    }

    public boolean isEnabled() {
        return !isOptionSet(Account.OPTION_DISABLED);
    }

    public boolean isOptionSet(final int option) {
        return ((options & (1 << option)) != 0);
    }

    public boolean setOption(final int option, final boolean value) {
        final int before = this.options;
        if (value) {
            this.options |= 1 << option;
        } else {
            this.options &= ~(1 << option);
        }
        return before != this.options;
    }

    public String getUsername() {
        return jid.getEscapedLocal();
    }

    public boolean setJid(final Jid next) {
        final Jid previousFull = this.jid;
        final Jid prev = this.jid != null ? this.jid.asBareJid() : null;
        final boolean changed = prev == null || (next != null && !prev.equals(next.asBareJid()));
        if (changed) {
            final AxolotlService oldAxolotlService = this.axolotlService;
            if (oldAxolotlService != null) {
                oldAxolotlService.destroy();
                this.jid = next;
                this.axolotlService = oldAxolotlService.makeNew();
            }
        }
        this.jid = next;
        return next != null && !next.equals(previousFull);
    }

    public String getServer() {
        return jid.getDomain();
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(final String password) {
        this.password = password;
    }

    public String getHostname() {
        return this.hostname == null ? "" : this.hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public boolean isOnion() {
        final String server = getServer();
        return server != null && server.endsWith(".onion");
    }

    public int getPort() {
        return this.port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public State getStatus() {
        if (isOptionSet(OPTION_DISABLED)) {
            return State.DISABLED;
        } else {
            return this.status;
        }
    }

    public State getLastErrorStatus() {
        return this.lastErrorStatus;
    }

    public void setStatus(final State status) {
        this.status = status;
        if (status.isError || status == State.ONLINE) {
            this.lastErrorStatus = status;
        }
    }

    public State getTrueStatus() {
        return this.status;
    }

    public boolean errorStatus() {
        return getStatus().isError();
    }

    public boolean hasErrorStatus() {
        return getXmppConnection() != null
                && (getStatus().isError() || getStatus() == State.CONNECTING)
                && (getXmppConnection().getAttempt() >= 3 ||
                    (Config.DISABLE_RECONNECT_IF_UNAUTHORIZED && getStatus() == State.UNAUTHORIZED)) ;
    }

    public Presence.Status getPresenceStatus() {
        return this.presenceStatus;
    }

    public void setPresenceStatus(Presence.Status status) {
        this.presenceStatus = status;
    }

    public String getPresenceStatusMessage() {
        return this.presenceStatusMessage;
    }

    public void setPresenceStatusMessage(String message) {
        this.presenceStatusMessage = message;
    }

    public String getResource() {
        return jid.getResource();
    }

    public void setResource(final String resource) {
        this.jid = this.jid.withResource(resource);
    }

    public Jid getJid() {
        return jid;
    }

    public JSONObject getKeys() {
        return keys;
    }

    public String getKey(final String name) {
        synchronized (this.keys) {
            return this.keys.optString(name, null);
        }
    }

    public int getKeyAsInt(final String name, int defaultValue) {
        String key = getKey(name);
        try {
            return key == null ? defaultValue : Integer.parseInt(key);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean setKey(final String keyName, final String keyValue) {
        synchronized (this.keys) {
            try {
                this.keys.put(keyName, keyValue);
                return true;
            } catch (final JSONException e) {
                return false;
            }
        }
    }

    public boolean setPrivateKeyAlias(String alias) {
        return setKey("private_key_alias", alias);
    }

    public String getPrivateKeyAlias() {
        return getKey("private_key_alias");
    }

    @Override
    public ContentValues getContentValues() {
        final ContentValues values = new ContentValues();
        values.put(UUID, uuid);
        values.put(USERNAME, jid.getLocal());
        values.put(SERVER, jid.getDomain());
        values.put(PASSWORD, password);
        values.put(OPTIONS, options);
        synchronized (this.keys) {
            values.put(KEYS, this.keys.toString());
        }
        values.put(ROSTERVERSION, rosterVersion);
        values.put(AVATAR, avatar);
        values.put(DISPLAY_NAME, displayName);
        values.put(HOSTNAME, hostname);
        values.put(PORT, port);
        values.put(STATUS, presenceStatus.toShowString());
        values.put(STATUS_MESSAGE, presenceStatusMessage);
        values.put(RESOURCE, jid.getResource());
        return values;
    }

    public AxolotlService getAxolotlService() {
        return axolotlService;
    }

    public void initAccountServices(final XmppConnectionService context) {
        this.axolotlService = new AxolotlService(this, context);
        this.pgpDecryptionService = new PgpDecryptionService(context);
        if (xmppConnection != null) {
            xmppConnection.addOnAdvancedStreamFeaturesAvailableListener(axolotlService);
        }
    }

    public PgpDecryptionService getPgpDecryptionService() {
        return this.pgpDecryptionService;
    }

    public XmppConnection getXmppConnection() {
        return this.xmppConnection;
    }

    public void setXmppConnection(final XmppConnection connection) {
        this.xmppConnection = connection;
    }

    public String getRosterVersion() {
        if (this.rosterVersion == null) {
            return "";
        } else {
            return this.rosterVersion;
        }
    }

    public void setRosterVersion(final String version) {
        this.rosterVersion = version;
    }

    public int countPresences() {
        return this.getSelfContact().getPresences().size();
    }

    public String getPgpSignature() {
        return getKey(KEY_PGP_SIGNATURE);
    }

    public boolean setPgpSignature(String signature) {
        return setKey(KEY_PGP_SIGNATURE, signature);
    }

    public boolean unsetPgpSignature() {
        synchronized (this.keys) {
            return keys.remove(KEY_PGP_SIGNATURE) != null;
        }
    }

    public long getPgpId() {
        synchronized (this.keys) {
            if (keys.has(KEY_PGP_ID)) {
                try {
                    return keys.getLong(KEY_PGP_ID);
                } catch (JSONException e) {
                    return 0;
                }
            } else {
                return 0;
            }
        }
    }

    public boolean setPgpSignId(long pgpID) {
        synchronized (this.keys) {
            try {
                if (pgpID == 0) {
                    keys.remove(KEY_PGP_ID);
                } else {
                    keys.put(KEY_PGP_ID, pgpID);
                }
            } catch (JSONException e) {
                return false;
            }
            return true;
        }
    }

    public Roster getRoster() {
        return this.roster;
    }

    public List<Bookmark> getBookmarks() {
        return this.bookmarks;
    }

    public List<Bookmark> getBookmarksToBePushed() {
        ArrayList<Bookmark> result = new ArrayList<>();
        for(Bookmark bookmark : bookmarks) {
            if (bookmark.push()) {
                result.add(bookmark);
            }
        }
        return result;
    }

    public void setBookmarks(final CopyOnWriteArrayList<Bookmark> bookmarks) {
        this.bookmarks = bookmarks;
    }

    public Set<Jid> getBookmarkedJids() {
        final Set<Jid> jids = new HashSet<>();
        for(final Bookmark bookmark : this.bookmarks) {
            final Jid jid = bookmark.getJid();
            if (jid != null) {
                jids.add(jid.asBareJid());
            }
        }
        return jids;
    }

    public boolean hasBookmarkFor(final Jid conferenceJid) {
        return getBookmark(conferenceJid) != null;
    }

    Bookmark getBookmark(final Jid jid) {
        for (final Bookmark bookmark : this.bookmarks) {
            if (bookmark.getJid() != null && jid.asBareJid().equals(bookmark.getJid().asBareJid())) {
                return bookmark;
            }
        }
        return null;
    }

    public boolean setAvatar(final String filename) {
        if (this.avatar != null && this.avatar.equals(filename)) {
            return false;
        } else {
            this.avatar = filename;
            return true;
        }
    }

    public String getAvatar() {
        return this.avatar;
    }

    public void activateGracePeriod(final long duration) {
        if (duration > 0) {
            this.mEndGracePeriod = SystemClock.elapsedRealtime() + duration;
        }
    }

    public void deactivateGracePeriod() {
        this.mEndGracePeriod = 0L;
    }

    public boolean inGracePeriod() {
        return SystemClock.elapsedRealtime() < this.mEndGracePeriod;
    }

    public String getShareableUri() {
        List<XmppUri.Fingerprint> fingerprints = this.getFingerprints();
        String uri = "xmpp:" + this.getJid().asBareJid().toEscapedString();
        if (fingerprints.size() > 0) {
            return XmppUri.getFingerprintUri(uri, fingerprints, ';');
        } else {
            return uri;
        }
    }

    public String getShareableLink() {
        List<XmppUri.Fingerprint> fingerprints = this.getFingerprints();
        String uri = "https://conversations.im/i/" + XmppUri.lameUrlEncode(this.getJid().asBareJid().toEscapedString());
        if (fingerprints.size() > 0) {
            return XmppUri.getFingerprintUri(uri, fingerprints, '&');
        } else {
            return uri;
        }
    }

    private List<XmppUri.Fingerprint> getFingerprints() {
        ArrayList<XmppUri.Fingerprint> fingerprints = new ArrayList<>();
        if (axolotlService == null) {
            return fingerprints;
        }
        fingerprints.add(new XmppUri.Fingerprint(XmppUri.FingerprintType.OMEMO, axolotlService.getOwnFingerprint().substring(2), axolotlService.getOwnDeviceId()));
        for (XmppAxolotlSession session : axolotlService.findOwnSessions()) {
            if (session.getTrust().isVerified() && session.getTrust().isActive()) {
                fingerprints.add(new XmppUri.Fingerprint(XmppUri.FingerprintType.OMEMO, session.getFingerprint().substring(2).replaceAll("\\s", ""), session.getRemoteAddress().getDeviceId()));
            }
        }
        return fingerprints;
    }

    public boolean isBlocked(final ListItem contact) {
        final Jid jid = contact.getJid();
        return jid != null && (blocklist.contains(jid.asBareJid()) || blocklist.contains(Jid.ofDomain(jid.getDomain())));
    }

    public boolean isBlocked(final Jid jid) {
        return jid != null && blocklist.contains(jid.asBareJid());
    }

    public Collection<Jid> getBlocklist() {
        return this.blocklist;
    }

    public void clearBlocklist() {
        getBlocklist().clear();
    }

    public boolean isOnlineAndConnected() {
        return this.getStatus() == State.ONLINE && this.getXmppConnection() != null;
    }

    public enum State {
        DISABLED(false, false),
        OFFLINE(false),
        CONNECTING(false),
        ONLINE(false),
        NO_INTERNET(false),
        UNAUTHORIZED,
        SERVER_NOT_FOUND,
        REGISTRATION_SUCCESSFUL(false),
        REGISTRATION_FAILED(true, false),
        REGISTRATION_WEB(true, false),
        REGISTRATION_CONFLICT(true, false),
        REGISTRATION_NOT_SUPPORTED(true, false),
        REGISTRATION_PLEASE_WAIT(true, false),
        REGISTRATION_PASSWORD_TOO_WEAK(true, false),
        TLS_ERROR,
        INCOMPATIBLE_SERVER,
        TOR_NOT_AVAILABLE,
        DOWNGRADE_ATTACK,
        SESSION_FAILURE,
        BIND_FAILURE,
        HOST_UNKNOWN,
        STREAM_ERROR,
        STREAM_OPENING_ERROR,
        POLICY_VIOLATION,
        PAYMENT_REQUIRED,
        MISSING_INTERNET_PERMISSION(false);

        private final boolean isError;
        private final boolean attemptReconnect;

        State(final boolean isError) {
            this(isError, true);
        }

        State(final boolean isError, final boolean reconnect) {
            this.isError = isError;
            this.attemptReconnect = reconnect;
        }

        State() {
            this(true, true);
        }

        public boolean isError() {
            return this.isError;
        }

        public boolean isAttemptReconnect() {
            return this.attemptReconnect;
        }

        public int getReadableId() {
            switch (this) {
                case DISABLED:
                    return R.string.account_status_disabled;
                case ONLINE:
                    return R.string.account_status_online;
                case CONNECTING:
                    return R.string.account_status_connecting;
                case OFFLINE:
                    return R.string.account_status_offline;
                case UNAUTHORIZED:
                    return R.string.account_status_unauthorized;
                case SERVER_NOT_FOUND:
                    return R.string.account_status_not_found;
                case NO_INTERNET:
                    return R.string.account_status_no_internet;
                case REGISTRATION_FAILED:
                    return R.string.account_status_regis_fail;
                case REGISTRATION_WEB:
                    return R.string.account_status_regis_web;
                case REGISTRATION_CONFLICT:
                    return R.string.account_status_regis_conflict;
                case REGISTRATION_SUCCESSFUL:
                    return R.string.account_status_regis_success;
                case REGISTRATION_NOT_SUPPORTED:
                    return R.string.account_status_regis_not_sup;
                case TLS_ERROR:
                    return R.string.account_status_tls_error;
                case INCOMPATIBLE_SERVER:
                    return R.string.account_status_incompatible_server;
                case TOR_NOT_AVAILABLE:
                    return R.string.account_status_tor_unavailable;
                case BIND_FAILURE:
                    return R.string.account_status_bind_failure;
                case SESSION_FAILURE:
                    return R.string.session_failure;
                case DOWNGRADE_ATTACK:
                    return R.string.sasl_downgrade;
                case HOST_UNKNOWN:
                    return R.string.account_status_host_unknown;
                case POLICY_VIOLATION:
                    return R.string.account_status_policy_violation;
                case REGISTRATION_PLEASE_WAIT:
                    return R.string.registration_please_wait;
                case REGISTRATION_PASSWORD_TOO_WEAK:
                    return R.string.registration_password_too_weak;
                case STREAM_ERROR:
                    return R.string.account_status_stream_error;
                case STREAM_OPENING_ERROR:
                    return R.string.account_status_stream_opening_error;
                case PAYMENT_REQUIRED:
                    return R.string.payment_required;
                case MISSING_INTERNET_PERMISSION:
                    return R.string.missing_internet_permission;
                default:
                    return R.string.account_status_unknown;
            }
        }
    }
}
