package com.acpc.port;

import jcifs.CIFSContext;
import jcifs.config.BaseConfiguration;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Minimal jcifs-ng wrapper: list/download from an SMB share. */
public final class SmbClient {

    public static class Entry {
        public final String name;
        public final long size;

        Entry(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    private final CIFSContext ctx;

    public SmbClient(String host, String user, String pass) throws IOException {
        Properties props = new Properties();
        props.setProperty("jcifs.smb.client.minVersion", "SMB202");
        props.setProperty("jcifs.smb.client.maxVersion", "SMB311");
        BaseConfiguration base = new PropertyConfiguration(props);
        CIFSContext anonymous = new BaseContext(base);
        if (user == null || user.isEmpty()) {
            ctx = anonymous;
        } else {
            ctx = anonymous.withCredentials(new NtlmPasswordAuthenticator(null, user, pass));
        }
        this.host = host;
    }

    private final String host;

    private String normalizeShare(String sharePath) {
        String p = sharePath.trim();
        if (p.isEmpty()) p = "/Roms/ROMs/gc";
        if (!p.startsWith("/")) p = "/" + p;
        if (!p.endsWith("/")) p = p + "/";
        return p;
    }

    public List<Entry> listRomFiles(String sharePath) throws IOException {
        String url = "smb://" + host + normalizeShare(sharePath);
        List<Entry> out = new ArrayList<>();
        try (SmbFile dir = new SmbFile(url, ctx)) {
            SmbFile[] files = dir.listFiles();
            if (files != null) {
                for (SmbFile f : files) {
                    String n = f.getName();
                    if (n.endsWith("/")) continue;
                    String lower = n.toLowerCase();
                    for (String ext : RomManager.ROM_EXTS) {
                        if (lower.endsWith(ext)) {
                            out.add(new Entry(n, f.length()));
                            break;
                        }
                    }
                }
            }
        }
        return out;
    }

    public InputStream open(String sharePath, String name) throws IOException {
        String url = "smb://" + host + normalizeShare(sharePath) + name;
        SmbFile f = new SmbFile(url, ctx);
        return f.getInputStream();
    }
}
