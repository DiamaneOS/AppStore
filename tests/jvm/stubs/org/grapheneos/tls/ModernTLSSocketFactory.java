package org.grapheneos.tls;
public class ModernTLSSocketFactory extends javax.net.ssl.SSLSocketFactory {
    public String[] getDefaultCipherSuites() { return new String[0]; }
    public String[] getSupportedCipherSuites() { return new String[0]; }
    public java.net.Socket createSocket(java.net.Socket s, String h, int p, boolean c) { throw new UnsupportedOperationException(); }
    public java.net.Socket createSocket(String h, int p) { throw new UnsupportedOperationException(); }
    public java.net.Socket createSocket(String h, int p, java.net.InetAddress a, int l) { throw new UnsupportedOperationException(); }
    public java.net.Socket createSocket(java.net.InetAddress h, int p) { throw new UnsupportedOperationException(); }
    public java.net.Socket createSocket(java.net.InetAddress h, int p, java.net.InetAddress a, int l) { throw new UnsupportedOperationException(); }
}
