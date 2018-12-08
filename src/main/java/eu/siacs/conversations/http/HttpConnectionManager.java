package eu.siacs.conversations.http;

import android.os.Build;
import android.os.Looper;
import android.util.Log;

import org.apache.http.conn.ssl.StrictHostnameVerifier;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.AbstractConnectionManager;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.TLSSocketFactory;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;

public class HttpConnectionManager extends AbstractConnectionManager {

    private static Proxy corporateProxy = null;

    private static Pattern[] nonProxyHostsPatterns;

    private void initNonProxyHostsPatterns() {
            List<Pattern> patterns = new ArrayList<Pattern>();
            if (Config.HTTP_NO_PROXY != null) {
                    for (String nonProxyHost : Config.HTTP_NO_PROXY) {
                            if (nonProxyHost == null || nonProxyHost.length() <= 0) {
                                    continue;
                            }
                            String pattern = nonProxyHost;
                            pattern = pattern.replace(".", "\\.").replace("*", ".*");
                            patterns.add(Pattern.compile(pattern, Pattern.CASE_INSENSITIVE));
                    }
            }
            nonProxyHostsPatterns = patterns.toArray(new Pattern[patterns.size()]);
    }

    private final List<HttpDownloadConnection> downloadConnections = new ArrayList<>();
    private final List<HttpUploadConnection> uploadConnections = new ArrayList<>();

    public static final Executor EXECUTOR = Executors.newFixedThreadPool(4);

    public HttpConnectionManager(XmppConnectionService service) {
        super(service);
        initNonProxyHostsPatterns();
    }

    public static Proxy getProxy() {
        final InetAddress localhost;
        try {
            localhost = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        } catch (final UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(localhost, 9050));
        } else {
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(localhost, 8118));
        }
    }

    public static Proxy getCorporateProxy(HttpUrl url) {
        if (Config.HTTP_PROXY == null) {
                return Proxy.NO_PROXY;
        }
        if (url != null) {
            String host = url.host();
            if (host != null) {
                    for (Pattern pattern : nonProxyHostsPatterns) {
                            if (pattern.matcher(host).matches()) {
                                    return Proxy.NO_PROXY;
                            }
                    }
            }
        }
        if (corporateProxy != null) {
            return corporateProxy;
        }
        if(Looper.getMainLooper().isCurrentThread()) {
            new Thread(new Runnable() {
                @Override public void run() {
                    corporateProxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(Config.HTTP_PROXY, Config.HTTP_PROXY_PORT));
                }
            }).start();
            return Proxy.NO_PROXY;
        } else {
            corporateProxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(Config.HTTP_PROXY, Config.HTTP_PROXY_PORT));
            return corporateProxy;
        }
    }

    public void createNewDownloadConnection(Message message) {
        this.createNewDownloadConnection(message, false);
    }

    public void createNewDownloadConnection(final Message message, boolean interactive) {
        synchronized (this.downloadConnections) {
            for(HttpDownloadConnection connection : this.downloadConnections) {
                if (connection.getMessage() == message) {
                    Log.d(Config.LOGTAG, message.getConversation().getAccount().getJid().asBareJid() + ": download already in progress");
                    return;
                }
            }
            final HttpDownloadConnection connection = new HttpDownloadConnection(message, this);
            connection.init(interactive);
            this.downloadConnections.add(connection);
        }
    }

    public void createNewUploadConnection(final Message message, boolean delay) {
        synchronized (this.uploadConnections) {
            for (HttpUploadConnection connection : this.uploadConnections) {
                if (connection.getMessage() == message) {
                    Log.d(Config.LOGTAG, message.getConversation().getAccount().getJid().asBareJid() + ": upload already in progress");
                    return;
                }
            }
            HttpUploadConnection connection = new HttpUploadConnection(message, Method.determine(message.getConversation().getAccount()), this);
            connection.init(delay);
            this.uploadConnections.add(connection);
        }
    }

    void finishConnection(HttpDownloadConnection connection) {
        synchronized (this.downloadConnections) {
            this.downloadConnections.remove(connection);
        }
    }

    void finishUploadConnection(HttpUploadConnection httpUploadConnection) {
        synchronized (this.uploadConnections) {
            this.uploadConnections.remove(httpUploadConnection);
        }
    }

    OkHttpClient buildHttpClient(final HttpUrl url, final Account account, boolean interactive) {
        final String slotHostname = url.host();
        final boolean onionSlot = slotHostname.endsWith(".onion");
        final OkHttpClient.Builder builder = new OkHttpClient.Builder();
        //builder.addInterceptor(new HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.HEADERS));
        builder.writeTimeout(30, TimeUnit.SECONDS);
        builder.readTimeout(30, TimeUnit.SECONDS);
        setupTrustManager(builder, interactive);
        if (mXmppConnectionService.useTorToConnect() || account.isOnion() || onionSlot) {
            builder.proxy(HttpConnectionManager.getProxy()).build();
        } else {
            builder.proxy(HttpConnectionManager.getCorporateProxy(url)).build();
        }
        return builder.build();
    }

    private void setupTrustManager(final OkHttpClient.Builder builder, final boolean interactive) {
        final X509TrustManager trustManager;
        final HostnameVerifier hostnameVerifier = mXmppConnectionService.getMemorizingTrustManager().wrapHostnameVerifier(new StrictHostnameVerifier(), interactive);
        if (interactive) {
            trustManager = mXmppConnectionService.getMemorizingTrustManager().getInteractive();
        } else {
            trustManager = mXmppConnectionService.getMemorizingTrustManager().getNonInteractive();
        }
        try {
            final SSLSocketFactory sf = new TLSSocketFactory(new X509TrustManager[]{trustManager}, mXmppConnectionService.getRNG());
            builder.sslSocketFactory(sf, trustManager);
            builder.hostnameVerifier(hostnameVerifier);
        } catch (final KeyManagementException | NoSuchAlgorithmException ignored) {
        }
    }

    public static InputStream open(final String url, final boolean tor) throws IOException {
        return open(HttpUrl.get(url), tor);
    }

    public static InputStream open(final HttpUrl httpUrl, final boolean tor) throws IOException {
        final OkHttpClient.Builder builder = new OkHttpClient.Builder();
        if (tor) {
            builder.proxy(HttpConnectionManager.getProxy()).build();
        } else {
            builder.proxy(HttpConnectionManager.getCorporateProxy(httpUrl)).build();
        }
        final OkHttpClient client = builder.build();
        final Request request = new Request.Builder().get().url(httpUrl).build();
        final ResponseBody body = client.newCall(request).execute().body();
        if (body == null) {
            throw new IOException("No response body found");
        }
        return body.byteStream();
    }
}
