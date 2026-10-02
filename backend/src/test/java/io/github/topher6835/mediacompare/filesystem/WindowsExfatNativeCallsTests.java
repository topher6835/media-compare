package io.github.topher6835.mediacompare.filesystem;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import org.junit.jupiter.api.Test;

class WindowsExfatNativeCallsTests {
    @Test
    void capturesOpenErrorImmediatelyAndPassesNullSecurityAndTemplate() {
        var api = mock(WindowsExfatNativeCalls.Api.class);
        when(api.CreateFile(anyString(), anyInt(), anyInt(), isNull(), anyInt(), anyInt(), isNull()))
                .thenAnswer(call -> { Native.setLastError(32); return null; });
        var calls = new WindowsExfatNativeCalls(api);
        var failure = assertThrows(WindowsFileAccessException.class,
                () -> calls.open(Path.of("C:/A"), 0x80000000, 1, 3, 0x00200000));
        assertEquals(32, failure.nativeError());
        assertEquals(WindowsFileAccessException.Reason.UNCERTAIN, failure.reason());
        verify(api).CreateFile(anyString(), eq(0x80000000), eq(1), isNull(), eq(3), eq(0x00200000), isNull());
        verify(api, never()).CloseHandle(any());
    }

    @Test
    void parsesUnsignedFactsAndResizesFinalPathWithinBound() throws Exception {
        var api = mock(WindowsExfatNativeCalls.Api.class);
        HANDLE handle = new HANDLE(new Pointer(42));
        when(api.CreateFile(anyString(), anyInt(), anyInt(), isNull(), anyInt(), anyInt(), isNull())).thenReturn(handle);
        when(api.GetFileInformationByHandle(eq(handle), any())).thenAnswer(call -> {
            Pointer info = call.getArgument(1);
            info.setInt(0, 0x20);
            info.setLong(4, 116444736000000001L);
            info.setLong(12, 116444736000000002L);
            info.setLong(20, 116444736000000003L);
            info.setInt(28, 0xabcdef01);
            info.setInt(32, 1); info.setInt(36, 2);
            info.setInt(44, 0x80000000); info.setInt(48, 0xffffffff);
            return true;
        });
        String finalPath = "\\\\?\\C:\\" + "x".repeat(1100);
        when(api.GetFinalPathNameByHandle(eq(handle), any(), anyInt(), eq(0))).thenAnswer(call -> {
            char[] buffer = call.getArgument(1);
            if (buffer.length <= finalPath.length()) return finalPath.length() + 1;
            finalPath.getChars(0, finalPath.length(), buffer, 0);
            return finalPath.length();
        });
        var calls = new WindowsExfatNativeCalls(api);
        var owned = calls.open(Path.of("C:/A"), 0, 1, 3, 0);
        var observation = calls.observe(owned);
        assertEquals(4294967298L, observation.size());
        assertEquals("abcdef01", observation.volumeSerial());
        assertEquals("80000000ffffffff", observation.legacyIndex());
        assertEquals(300, observation.modifiedInstant().getNano());
        assertEquals(finalPath, observation.finalPath());
        verify(api, times(2)).GetFinalPathNameByHandle(eq(handle), any(), anyInt(), eq(0));
        when(api.GetFinalPathNameByHandle(eq(handle), any(), anyInt(), eq(0))).thenReturn(32768);
        assertThrows(WindowsFileAccessException.class, () -> calls.observe(owned));
        when(api.GetFinalPathNameByHandle(eq(handle), any(), anyInt(), eq(0)))
                .thenAnswer(call -> { Native.setLastError(6); return 0; });
        assertEquals(6, assertThrows(WindowsFileAccessException.class, () -> calls.observe(owned)).nativeError());
        when(api.CloseHandle(handle)).thenAnswer(call -> { Native.setLastError(5); return false; });
        assertEquals(5, assertThrows(WindowsFileAccessException.class, () -> calls.close(owned)).nativeError());
    }
}
