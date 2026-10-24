package com.cactuslauncher.game;

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;
import java.io.IOException;
import java.io.InputStream;

/** Streams an Ogg Vorbis file to PCM with JOrbis (pure Java, LGPL), so playback needs no native code. */
public final class OggVorbisDecoder {
    public interface Sink {
        void start(int sampleRate, int channels) throws IOException;
        /** [pcm][channel][offsets[channel] + i] for i below [samples], in -1..1; false stops decoding. */
        boolean write(float[][] pcm, int[] offsets, int samples) throws IOException;
    }

    private static final int CHUNK = 4096;

    private OggVorbisDecoder() {}

    public static void decode(InputStream input, Sink sink) throws IOException {
        SyncState sync = new SyncState();
        StreamState stream = new StreamState();
        Page page = new Page();
        Packet packet = new Packet();
        Info info = new Info();
        Comment comment = new Comment();
        DspState dsp = new DspState();
        Block block = new Block(dsp);
        sync.init();
        try {
            if (!read(sync, input) || sync.pageout(page) != 1) throw new IOException("Файл не похож на Ogg Vorbis.");
            stream.init(page.serialno());
            info.init();
            comment.init();
            if (stream.pagein(page) < 0 || stream.packetout(packet) != 1 || info.synthesis_headerin(comment, packet) < 0)
                throw new IOException("Повреждён заголовок Vorbis.");
            int headers = 1;
            while (headers < 3) {
                int result = sync.pageout(page);
                if (result == 1) {
                    stream.pagein(page);
                    while (headers < 3) {
                        result = stream.packetout(packet);
                        if (result == 0) break;
                        if (result < 0 || info.synthesis_headerin(comment, packet) < 0) throw new IOException("Повреждён заголовок Vorbis.");
                        headers++;
                    }
                } else if (result == 0 && !read(sync, input)) throw new IOException("Файл Vorbis оборван.");
            }
            if (info.channels < 1 || info.channels > 8 || info.rate < 8000 || info.rate > 192000) throw new IOException("Неподдерживаемый формат Vorbis.");
            sink.start(info.rate, info.channels);
            dsp.synthesis_init(info);
            block.init(dsp);
            float[][][] pcm = new float[1][][];
            int[] offsets = new int[info.channels];
            boolean end = false;
            while (!end) {
                int result = sync.pageout(page);
                if (result == 0) {
                    if (!read(sync, input)) end = true;
                    continue;
                }
                if (result < 0) continue; // a hole in the data: skip to the next page
                stream.pagein(page);
                while (true) {
                    result = stream.packetout(packet);
                    if (result == 0) break;
                    if (result < 0) continue;
                    if (block.synthesis(packet) == 0) dsp.synthesis_blockin(block);
                    int samples;
                    while ((samples = dsp.synthesis_pcmout(pcm, offsets)) > 0) {
                        if (!sink.write(pcm[0], offsets, samples)) return;
                        dsp.synthesis_read(samples);
                    }
                }
                if (page.eos() != 0) end = true;
            }
        } finally {
            stream.clear();
            block.clear();
            dsp.clear();
            info.clear();
            sync.clear();
        }
    }

    private static boolean read(SyncState sync, InputStream input) throws IOException {
        int index = sync.buffer(CHUNK);
        int count = input.read(sync.data, index, CHUNK);
        if (count <= 0) { sync.wrote(0); return false; }
        sync.wrote(count);
        return true;
    }
}
