package harness;

import java.io.File;
import java.nio.file.Files;
import java.security.SecureRandom;

// Makes the throwaway test key on first use and prints its public key in the app's format.
public final class KeyGen {
    public static void main(String[] args) throws Exception {
        File file = new File(args[0]);
        if (!file.exists()) {
            byte[] seed = new byte[40];
            new SecureRandom().nextBytes(seed);
            Files.write(file.toPath(), seed);
        }
        byte[] seed = Files.readAllBytes(file.toPath());
        System.out.print(TestKeyMaterial.publicKey(seed));
    }
}
