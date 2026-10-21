package com.cactuslauncher.game;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileStoreTest {
    @TempDir Path temp;

    @Test
    void fileWithoutAvatarsLoads() throws Exception {
        Path file = Files.writeString(temp.resolve("profiles.properties"),
            "#Cactus Launcher offline profiles\nactive=b\norder=a,b\nprofile.a=Steve\nprofile.b=Alex_2\n");
        var snapshot = new ProfileStore(file).load();
        assertEquals("b", snapshot.activeId());
        assertEquals(2, snapshot.profiles().size());
        assertNull(snapshot.profiles().get(0).avatar());
        assertNull(snapshot.profiles().get(1).avatar());
    }

    @Test
    void avatarSurvivesRenameAndIsRemovedWithProfile() throws Exception {
        var store = new ProfileStore(temp.resolve("profiles.properties"));
        String first = store.load().profiles().getFirst().id();
        String second = store.add("Second").activeId();
        store.setAvatar(second, "0123456789abcdef");
        assertEquals("0123456789abcdef", store.rename(second, "Renamed").profiles().get(1).avatar());
        assertNull(store.load().profiles().getFirst().avatar());
        assertNull(store.setAvatar(second, null).profiles().get(1).avatar());
        store.setAvatar(second, "0123456789abcdef");
        store.delete(second);
        assertFalse(Files.readString(temp.resolve("profiles.properties")).contains("avatar."));
        assertEquals(first, store.load().activeId());
    }

    @Test
    void malformedAvatarValueIsIgnoredAndRejected() throws Exception {
        Path file = Files.writeString(temp.resolve("profiles.properties"),
            "active=a\norder=a\nprofile.a=Steve\navatar.a=../../etc/passwd\n");
        var store = new ProfileStore(file);
        assertNull(store.load().profiles().getFirst().avatar());
        assertThrows(IllegalArgumentException.class, () -> store.setAvatar("a", "../x"));
        assertThrows(IllegalArgumentException.class, () -> store.setAvatar("missing", "0123456789abcdef"));
    }
}
