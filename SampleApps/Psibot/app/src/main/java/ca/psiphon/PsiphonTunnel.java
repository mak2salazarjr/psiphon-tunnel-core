/*
 * Copyright (c) 2015, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package ca.psiphon;

import android.annotation.TargetApi;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import org.apache.http.conn.util.InetAddressUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import go.psi.Psi;

public class PsiphonTunnel extends Psi.PsiphonProvider.Stub {

    public interface HostService {
        public String getAppName();
        public Context getContext();
        public VpnService getVpnService();
        public VpnService.Builder newVpnServiceBuilder();
        public String getPsiphonConfig();
        public void onDiagnosticMessage(String message);
        public void onAvailableEgressRegions(List<String> regions);
        public void onSocksProxyPortInUse(int port);
        public void onHttpProxyPortInUse(int port);
        public void onListeningSocksProxyPort(int port);
        public void onListeningHttpProxyPort(int port);
        public void onUpstreamProxyError(String message);
        public void onConnecting();
        public void onConnected();
        public void onHomepage(String url);
        public void onClientRegion(String region);
        public void onClientUpgradeDownloaded(String filename);
        public void onSplitTunnelRegion(String region);
        public void onUntunneledAddress(String address);
        public void onBytesTransferred(long sent, long received);
    }

    private final HostService mHostService;
    private PrivateAddress mPrivateAddress;
    private ParcelFileDescriptor mTunFd;
    private int mLocalSocksProxyPort;
    private boolean mRoutingThroughTunnel;
    private Thread mTun2SocksThread;

    // Only one PsiphonVpn instance may exist at a time, as the underlying
    // go.psi.Psi and tun2socks implementations each contain global state.
    private static PsiphonTunnel mPsiphonTunnel;

    public static synchronized PsiphonTunnel newPsiphonVpn(HostService hostService) {
        if (mPsiphonTunnel != null) {
            mPsiphonTunnel.stop();
        }
        // Load the native go code embedded in psi.aar
        System.loadLibrary("gojni");
        mPsiphonTunnel = new PsiphonTunnel(hostService);
        return mPsiphonTunnel;
    }

    private PsiphonTunnel(HostService hostService) {
        mHostService = hostService;
        mLocalSocksProxyPort = 0;
        mRoutingThroughTunnel = false;
    }

    public Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    //----------------------------------------------------------------------------------------------
    // Public API
    //----------------------------------------------------------------------------------------------

    // To start, call in sequence: startRouting(), then startTunneling(). After startRouting()
    // succeeds, the caller must call stop() to clean up.

    // Returns true when the VPN routing is established; returns false if the VPN could not
    // be started due to lack of prepare or revoked permissions (called should re-prepare and
    // try again); throws exception for other error conditions.
    public synchronized boolean startRouting() throws Exception {
        return startVpn();
    }

    // Throws an exception in error conditions. In the case of an exception, the routing
    // started by startRouting() is not immediately torn down (this allows the caller to control
    // exactly when VPN routing is stopped); caller should call stop() to clean up.
    public synchronized void startTunneling(String embeddedServerEntries) throws Exception {
        startPsiphon(embeddedServerEntries);
    }

    public synchronized void restartPsiphon() throws Exception {
        stopPsiphon();
        startPsiphon("");
    }

    public synchronized void stop() {
        stopVpn();
        stopPsiphon();
        mLocalSocksProxyPort = 0;
    }

    //----------------------------------------------------------------------------------------------
    // VPN Routing
    //----------------------------------------------------------------------------------------------

    private final static String VPN_INTERFACE_NETMASK = "255.255.255.0";
    private final static int VPN_INTERFACE_MTU = 1500;
    private final static int UDPGW_SERVER_PORT = 7300;
    private final static String DEFAULT_DNS_SERVER = "8.8.4.4";

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    private boolean startVpn() throws Exception {

        mPrivateAddress = selectPrivateAddress();

        Locale previousLocale = Locale.getDefault();

        final String errorMessage = "startVpn failed";
        try {
            // Workaround for https://code.google.com/p/android/issues/detail?id=61096
            Locale.setDefault(new Locale("en"));

            mTunFd = mHostService.newVpnServiceBuilder()
                    .setSession(mHostService.getAppName())
                    .setMtu(VPN_INTERFACE_MTU)
                    .addAddress(mPrivateAddress.mIpAddress, mPrivateAddress.mPrefixLength)
                    .addRoute("0.0.0.0", 0)
                    .addRoute(mPrivateAddress.mSubnet, mPrivateAddress.mPrefixLength)
                    .addDnsServer(mPrivateAddress.mRouter)
                    .establish();
            if (mTunFd == null) {
                // As per http://developer.android.com/reference/android/net/VpnService.Builder.html#establish%28%29,
                // this application is no longer prepared or was revoked.
                return false;
            }
            mHostService.onDiagnosticMessage("VPN established");

        } catch(IllegalArgumentException e) {
            throw new Exception(errorMessage, e);
        } catch(IllegalStateException e) {
            throw new Exception(errorMessage, e);
        } catch(SecurityException e) {
            throw new Exception(errorMessage, e);
        } finally {
            // Restore the original locale.
            Locale.setDefault(previousLocale);
        }

        return true;
    }

    private synchronized void setLocalSocksProxyPort(int port) {
        mLocalSocksProxyPort = port;
    }

    private synchronized void routeThroughTunnel() {
        if (mRoutingThroughTunnel) {
            return;
        }
        mRoutingThroughTunnel = true;
        String socksServerAddress = "127.0.0.1:" + Integer.toString(mLocalSocksProxyPort);
        String udpgwServerAddress = "127.0.0.1:" + Integer.toString(UDPGW_SERVER_PORT);
        startTun2Socks(
                mTunFd,
                VPN_INTERFACE_MTU,
                mPrivateAddress.mRouter,
                VPN_INTERFACE_NETMASK,
                socksServerAddress,
                udpgwServerAddress,
                true);
        mHostService.onDiagnosticMessage("routing through tunnel");

        // TODO: should double-check tunnel routing; see:
        // https://bitbucket.org/psiphon/psiphon-circumvention-system/src/1dc5e4257dca99790109f3bf374e8ab3a0ead4d7/Android/PsiphonAndroidLibrary/src/com/psiphon3/psiphonlibrary/TunnelCore.java?at=default#cl-779
    }

    private void stopVpn() {
        if (mTunFd != null) {
            try {
                mTunFd.close();
            } catch (IOException e) {
            }
            mTunFd = null;
        }
        waitStopTun2Socks();
        mRoutingThroughTunnel = false;
    }
    
    //----------------------------------------------------------------------------------------------
    // PsiphonProvider (Core support) interface implementation
    //----------------------------------------------------------------------------------------------

    @Override
    public void Notice(String noticeJSON) {
        handlePsiphonNotice(noticeJSON);
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    @Override
    public void BindToDevice(long fileDescriptor) throws Exception {
        if (!mHostService.getVpnService().protect((int)fileDescriptor)) {
            throw new Exception("protect socket failed");
        }
    }

    @Override
    public long HasNetworkConnectivity() {
        // TODO: change to bool return value once gobind supports that type
        return hasNetworkConnectivity(mHostService.getContext()) ? 1 : 0;
    }

    @Override
    public String GetDnsServer() {
        String dnsResolver = null;
        try {
            dnsResolver = getFirstActiveNetworkDnsResolver(mHostService.getVpnService());
        } catch (Exception e) {
            mHostService.onDiagnosticMessage("failed to get active network DNS resolver: " + e.getMessage());
            dnsResolver = DEFAULT_DNS_SERVER;
        }
        return dnsResolver;
    }

    //----------------------------------------------------------------------------------------------
    // Psiphon Tunnel Core
    //----------------------------------------------------------------------------------------------

    private void startPsiphon(String embeddedServerEntries) throws Exception {
        stopPsiphon();
        mHostService.onDiagnosticMessage("starting Psiphon library");
        try {
            boolean isVpnMode = (mTunFd != null);
            Psi.Start(
                loadPsiphonConfig(mHostService.getContext(), isVpnMode),
                embeddedServerEntries,
                this,
                isVpnMode);
        } catch (java.lang.Exception e) {
            throw new Exception("failed to start Psiphon library", e);
        }
        mHostService.onDiagnosticMessage("Psiphon library started");
    }

    private void stopPsiphon() {
        mHostService.onDiagnosticMessage("stopping Psiphon library");
        Psi.Stop();
        mHostService.onDiagnosticMessage("Psiphon library stopped");
    }

    private String loadPsiphonConfig(Context context, boolean isVpnMode)
            throws IOException, JSONException {

        // Load settings from the raw resource JSON config file and
        // update as necessary. Then write JSON to disk for the Go client.
        JSONObject json = new JSONObject(mHostService.getPsiphonConfig());
        
        // On Android, these directories must be set to the app private storage area.
        // The Psiphon library won't be able to use its current working directory
        // and the standard temporary directories do not exist.
        json.put("DataStoreDirectory", context.getFilesDir());
        json.put("DataStoreTempDirectory", context.getCacheDir());

        // Note: onConnecting/onConnected logic assumes 1 tunnel connection
        json.put("TunnelPoolSize", 1);

        // Continue to run indefinitely until connected
        json.put("EstablishTunnelTimeoutSeconds", 0);

        // This parameter is for stats reporting
        json.put("TunnelWholeDevice", isVpnMode ? 1 : 0);

        // Enable tunnel auto-reconnect after a threshold number of port
        // forward failures. By default, this mechanism is disabled in
        // tunnel-core due to the chance of false positives due to
        // bad user input. Since VpnService mode resolves domain names
        // differently (udpgw), invalid domain name user input won't result
        // in SSH port forward failures.
        if (isVpnMode) {
            json.put("PortForwardFailureThreshold", 10);
        }

        json.put("EmitBytesTransferred", true);

        if (mLocalSocksProxyPort != 0) {
            // When mLocalSocksProxyPort is set, tun2socks is already configured
            // to use that port value. So we force use of the same port.
            // A side-effect of this is that changing the SOCKS port preference
            // has no effect with restartPsiphon(), a full stop() is necessary.
            json.put("LocalSocksProxyPort", mLocalSocksProxyPort);
        }
        
        json.put("UseIndistinguishableTLS", true);

        // TODO: doesn't work due to OpenSSL version incompatibility; try using
        // the KeyStore API to build a local copy of trusted CAs cert files.
        //
        //if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
        //    json.put("SystemCACertificateDirectory", "/system/etc/security/cacerts");
        //}

        return json.toString();
    }

    private void handlePsiphonNotice(String noticeJSON) {
        try {
            // All notices are sent on as diagnostic messages
            // except those that may contain private user data.
            boolean diagnostic = true;
            
            JSONObject notice = new JSONObject(noticeJSON);
            String noticeType = notice.getString("noticeType");
            
            if (noticeType.equals("Tunnels")) {
                int count = notice.getJSONObject("data").getInt("count");
                if (count > 0) {
                    routeThroughTunnel();
                    mHostService.onConnected();
                } else {
                    mHostService.onConnecting();
                }

            } else if (noticeType.equals("AvailableEgressRegions")) {
                JSONArray egressRegions = notice.getJSONObject("data").getJSONArray("regions");
                ArrayList<String> regions = new ArrayList<String>();
                for (int i=0; i<egressRegions.length(); i++) {
                    regions.add(egressRegions.getString(i));
                }
                mHostService.onAvailableEgressRegions(regions);
                
            } else if (noticeType.equals("SocksProxyPortInUse")) {
                mHostService.onSocksProxyPortInUse(notice.getJSONObject("data").getInt("port"));

            } else if (noticeType.equals("HttpProxyPortInUse")) {
                mHostService.onHttpProxyPortInUse(notice.getJSONObject("data").getInt("port"));

            } else if (noticeType.equals("ListeningSocksProxyPort")) {
                int port = notice.getJSONObject("data").getInt("port");
                setLocalSocksProxyPort(port);
                mHostService.onListeningSocksProxyPort(port);

            } else if (noticeType.equals("ListeningHttpProxyPort")) {
                int port = notice.getJSONObject("data").getInt("port");
                mHostService.onListeningHttpProxyPort(port);

            } else if (noticeType.equals("UpstreamProxyError")) {
                mHostService.onUpstreamProxyError(notice.getJSONObject("data").getString("message"));

            } else if (noticeType.equals("ClientUpgradeDownloaded")) {
                mHostService.onHomepage(notice.getJSONObject("data").getString("filename"));

            } else if (noticeType.equals("Homepage")) {
                mHostService.onHomepage(notice.getJSONObject("data").getString("url"));

            } else if (noticeType.equals("ClientRegion")) {
                mHostService.onClientRegion(notice.getJSONObject("data").getString("region"));

            } else if (noticeType.equals("SplitTunnelRegion")) {
                mHostService.onSplitTunnelRegion(notice.getJSONObject("data").getString("region"));

            } else if (noticeType.equals("UntunneledAddress")) {
                mHostService.onUntunneledAddress(notice.getJSONObject("data").getString("address"));

            } else if (noticeType.equals("BytesTransferred")) {
                JSONObject data = notice.getJSONObject("data");
                mHostService.onBytesTransferred(data.getLong("sent"), data.getLong("received"));
            }

            if (diagnostic) {
                String diagnosticMessage = noticeType + ": " + notice.getJSONObject("data").toString();
                mHostService.onDiagnosticMessage(diagnosticMessage);
            }

        } catch (JSONException e) {
            // Ignore notice
        }
    }

    //----------------------------------------------------------------------------------------------
    // Tun2Socks
    //----------------------------------------------------------------------------------------------

    @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR1)
    private void startTun2Socks(
            final ParcelFileDescriptor vpnInterfaceFileDescriptor,
            final int vpnInterfaceMTU,
            final String vpnIpAddress,
            final String vpnNetMask,
            final String socksServerAddress,
            final String udpgwServerAddress,
            final boolean udpgwTransparentDNS) {
        mTun2SocksThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runTun2Socks(
                        vpnInterfaceFileDescriptor.getFd(),
                        vpnInterfaceMTU,
                        vpnIpAddress,
                        vpnNetMask,
                        socksServerAddress,
                        udpgwServerAddress,
                        udpgwTransparentDNS ? 1 : 0);
            }
        });
        mTun2SocksThread.start();
        mHostService.onDiagnosticMessage("tun2socks started");
    }

    private void waitStopTun2Socks() {
        if (mTun2SocksThread != null) {
            try {
                // Assumes mTunFd has been closed, which signals tun2socks to exit
                mTun2SocksThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mTun2SocksThread = null;
            mHostService.onDiagnosticMessage("tun2socks stopped");
        }
    }

    public static void logTun2Socks(String level, String channel, String msg) {
        String logMsg = "tun2socks: " + level + "(" + channel + "): " + msg;
        mPsiphonTunnel.mHostService.onDiagnosticMessage(logMsg);
    }

    private native static int runTun2Socks(
            int vpnInterfaceFileDescriptor,
            int vpnInterfaceMTU,
            String vpnIpAddress,
            String vpnNetMask,
            String socksServerAddress,
            String udpgwServerAddress,
            int udpgwTransparentDNS);

    static {
        System.loadLibrary("tun2socks");
    }

    //----------------------------------------------------------------------------------------------
    // Implementation: Network Utils
    //----------------------------------------------------------------------------------------------

    private static boolean hasNetworkConnectivity(Context context) {
        ConnectivityManager connectivityManager =
                (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
        return networkInfo != null && networkInfo.isConnected();
    }

    private static class PrivateAddress {
        final public String mIpAddress;
        final public String mSubnet;
        final public int mPrefixLength;
        final public String mRouter;
        public PrivateAddress(String ipAddress, String subnet, int prefixLength, String router) {
            mIpAddress = ipAddress;
            mSubnet = subnet;
            mPrefixLength = prefixLength;
            mRouter = router;
        }
    }

    private static PrivateAddress selectPrivateAddress() throws Exception {
        // Select one of 10.0.0.1, 172.16.0.1, or 192.168.0.1 depending on
        // which private address range isn't in use.

        Map<String, PrivateAddress> candidates = new HashMap<String, PrivateAddress>();
        candidates.put( "10", new PrivateAddress("10.0.0.1",    "10.0.0.0",     8, "10.0.0.2"));
        candidates.put("172", new PrivateAddress("172.16.0.1",  "172.16.0.0",  12, "172.16.0.2"));
        candidates.put("192", new PrivateAddress("192.168.0.1", "192.168.0.0", 16, "192.168.0.2"));
        candidates.put("169", new PrivateAddress("169.254.1.1", "169.254.1.0", 24, "169.254.1.2"));

        List<NetworkInterface> netInterfaces;
        try {
            netInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
        } catch (SocketException e) {
            throw new Exception("selectPrivateAddress failed", e);
        }

        for (NetworkInterface netInterface : netInterfaces) {
            for (InetAddress inetAddress : Collections.list(netInterface.getInetAddresses())) {
                String ipAddress = inetAddress.getHostAddress();
                if (InetAddressUtils.isIPv4Address(ipAddress)) {
                    if (ipAddress.startsWith("10.")) {
                        candidates.remove("10");
                    }
                    else if (
                            ipAddress.length() >= 6 &&
                                    ipAddress.substring(0, 6).compareTo("172.16") >= 0 &&
                                    ipAddress.substring(0, 6).compareTo("172.31") <= 0) {
                        candidates.remove("172");
                    }
                    else if (ipAddress.startsWith("192.168")) {
                        candidates.remove("192");
                    }
                }
            }
        }

        if (candidates.size() > 0) {
            return candidates.values().iterator().next();
        }

        throw new Exception("no private address available");
    }

    public static String getFirstActiveNetworkDnsResolver(Context context)
            throws Exception {
        Collection<InetAddress> dnsResolvers = getActiveNetworkDnsResolvers(context);
        if (!dnsResolvers.isEmpty()) {
            // strip the leading slash e.g., "/192.168.1.1"
            String dnsResolver = dnsResolvers.iterator().next().toString();
            if (dnsResolver.startsWith("/")) {
                dnsResolver = dnsResolver.substring(1);
            }
            return dnsResolver;
        }
        throw new Exception("no active network DNS resolver");
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static Collection<InetAddress> getActiveNetworkDnsResolvers(Context context)
            throws Exception {
        final String errorMessage = "getActiveNetworkDnsResolvers failed";
        ArrayList<InetAddress> dnsAddresses = new ArrayList<InetAddress>();
        try {
            // Hidden API
            // - only available in Android 4.0+
            // - no guarantee will be available beyond 4.2, or on all vendor devices
            ConnectivityManager connectivityManager =
                    (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Class<?> LinkPropertiesClass = Class.forName("android.net.LinkProperties");
            Method getActiveLinkPropertiesMethod = ConnectivityManager.class.getMethod("getActiveLinkProperties", new Class []{});
            Object linkProperties = getActiveLinkPropertiesMethod.invoke(connectivityManager);
            if (linkProperties != null) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    Method getDnsesMethod = LinkPropertiesClass.getMethod("getDnses", new Class []{});
                    Collection<?> dnses = (Collection<?>)getDnsesMethod.invoke(linkProperties);
                    for (Object dns : dnses) {
                        dnsAddresses.add((InetAddress)dns);
                    }
                } else {
                    // LinkProperties is public in API 21 (and the DNS function signature has changed)
                    for (InetAddress dns : ((LinkProperties)linkProperties).getDnsServers()) {
                        dnsAddresses.add(dns);
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            throw new Exception(errorMessage, e);
        } catch (NoSuchMethodException e) {
            throw new Exception(errorMessage, e);
        } catch (IllegalArgumentException e) {
            throw new Exception(errorMessage, e);
        } catch (IllegalAccessException e) {
            throw new Exception(errorMessage, e);
        } catch (InvocationTargetException e) {
            throw new Exception(errorMessage, e);
        } catch (NullPointerException e) {
            throw new Exception(errorMessage, e);
        }

        return dnsAddresses;
    }

    //----------------------------------------------------------------------------------------------
    // Exception
    //----------------------------------------------------------------------------------------------

    public static class Exception extends java.lang.Exception {
        private static final long serialVersionUID = 1L;
        public Exception(String message) {
            super(message);
        }
        public Exception(String message, Throwable cause) {
            super(message + ": " + cause.getMessage());
        }
    }
}