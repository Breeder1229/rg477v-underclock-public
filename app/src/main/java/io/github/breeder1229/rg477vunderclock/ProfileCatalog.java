package io.github.breeder1229.rg477vunderclock;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ProfileCatalog {
    public static final String[] NODES = {"cpu0", "cpu4", "cpu7", "gpu"};
    public static final String[] NODE_LABELS = {"A510 ×4", "A715 ×3", "A715 ×1", "GPU"};
    public static final String[] KEYS = {"small", "medium", "large"};
    public static final String[] LABELS = {"Small", "Medium", "Large"};
    public static final String[] DESCRIPTIONS = {"성능 여유", "균형 후보", "강한 제한"};
    public static final class Profile {
        public final String key, name, description;
        public final long[] targets;
        Profile(int index, long[] targets) {
            key = KEYS[index]; name = LABELS[index]; description = DESCRIPTIONS[index]; this.targets = targets;
        }
    }
    public static List<Profile> load(Context context) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream input = context.getAssets().open("MT6897-RG477V.json")) {
            byte[] block = new byte[4096]; int length;
            while ((length = input.read(block)) != -1) buffer.write(block, 0, length);
        }
        JSONObject document = new JSONObject(buffer.toString(StandardCharsets.UTF_8.name()));
        if (document.getInt("schemaVersion") != 2 || !"MT6897".equals(document.getString("socModel")))
            throw new IllegalArgumentException("잘못된 프로필 스키마");
        JSONArray rows = document.getJSONArray("profiles");
        if (rows.length() != 3) throw new IllegalArgumentException("프로필 수 확인 필요");
        List<Profile> profiles = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject profile = rows.getJSONObject(i);
            if (!profile.getString("id").contains("_" + KEYS[i] + "_"))
                throw new IllegalArgumentException("프로필 순서 확인 필요");
            JSONObject cpu = profile.getJSONObject("maxFrequencies");
            long[] targets = {cpu.getLong("0"), cpu.getLong("4"), cpu.getLong("7"), profile.getLong("gpuMaxFrequencyHz")};
            for (long target : targets) if (target <= 0) throw new IllegalArgumentException("유효하지 않은 목표 클럭");
            profiles.add(new Profile(i, targets));
        }
        return profiles;
    }
}
