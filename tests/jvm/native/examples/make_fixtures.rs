// Writes the APK, delta and brotli fixtures of the install tests: make_fixtures <dir>

use std::{env, fs, io::Write, path::Path};

// Deterministic data that looks a little like an APK
fn blob(seed: u32, len: usize) -> Vec<u8> {
    let mut x = seed.wrapping_mul(2_654_435_761) | 1;
    let mut out = b"PK\x03\x04".to_vec();
    while out.len() < len {
        x ^= x << 13;
        x ^= x >> 17;
        x ^= x << 5;
        match x % 4 {
            0 => out.extend_from_slice(b"classes.dex\0resources.arsc\0"),
            1 => out.extend(std::iter::repeat_n(0, (x % 100) as usize)),
            _ => out.extend_from_slice(&x.to_le_bytes()),
        }
    }
    out.truncate(len);
    out
}

fn diff(old: &[u8], new: &[u8]) -> Vec<u8> {
    let mut sentinel = old.to_vec();
    sentinel.push(0);
    let mut patch = Vec::new();
    ina::diff(&sentinel, new, &mut patch).unwrap();
    patch
}

fn brotli(data: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    {
        let mut w = brotli::CompressorWriter::new(&mut out, 4096, 9, 22);
        w.write_all(data).unwrap();
    }
    out
}

fn main() {
    let dir = env::args().nth(1).expect("output directory");
    let dir = Path::new(&dir);
    fs::create_dir_all(dir).unwrap();
    let old = blob(1, 48_000);
    let mut new = old.clone();
    new[100..200].fill(9);
    new.splice(5000..5000, blob(2, 3000));
    let mut other = new.clone();
    other[40_000] ^= 1;

    // A control stream that writes 64 MiB of zeros
    let mut stream = Vec::new();
    for _ in 0..64 {
        stream.extend_from_slice(&[0, 0x80, 0x80, 0x40]);
        stream.resize(stream.len() + (1 << 20), 0);
        stream.push(0);
    }
    let mut bomb = vec![0x7c, 0x6c, 0x95, 0x5c, 1, 0, 0, 0, 0];
    bomb.extend(zstd::encode_all(&stream[..], 19).unwrap());

    for (name, data) in [
        ("old.apk", old.clone()),
        ("new.apk", new.clone()),
        ("good.ina", diff(&old, &new)),
        ("wrong.ina", diff(&old, &other)),
        ("bomb.ina", bomb),
        ("good.br", brotli(&new)),
        ("wrong.br", brotli(&other)),
        ("bomb.br", brotli(&vec![0; 64 << 20])),
    ] {
        fs::write(dir.join(name), data).unwrap();
    }
}
