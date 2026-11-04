package harness;

import java.util.Arrays;
import java.util.Base64;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

// A throwaway signify-format test key: 32 bytes of Ed25519 seed and an 8 byte key id.
public final class TestKeyMaterial {
    static byte[] header(byte[] seed) {
        byte[] h = new byte[10];
        h[0] = 'E';
        h[1] = 'd';
        System.arraycopy(seed, 32, h, 2, 8);
        return h;
    }

    public static String publicKey(byte[] seed) {
        byte[] pub = new Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().getEncoded();
        byte[] out = Arrays.copyOf(header(seed), 42);
        System.arraycopy(pub, 0, out, 10, 32);
        return Base64.getEncoder().encodeToString(out);
    }

    public static String sign(byte[] seed, byte[] message) {
        Ed25519Signer s = new Ed25519Signer();
        s.init(true, new Ed25519PrivateKeyParameters(seed, 0));
        s.update(message, 0, message.length);
        byte[] out = Arrays.copyOf(header(seed), 74);
        System.arraycopy(s.generateSignature(), 0, out, 10, 64);
        return Base64.getEncoder().encodeToString(out);
    }
}
