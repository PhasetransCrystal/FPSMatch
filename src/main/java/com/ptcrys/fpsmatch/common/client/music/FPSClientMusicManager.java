package com.ptcrys.fpsmatch.common.client.music;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.core.data.music.OnlineMusic;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.sound.sampled.*;

/**
 * 客户端音乐管理器，用于播放和停止音乐。
 * <p>
 * 该类通过 Minecraft 的音频系统播放音乐，并支持事件广播，允许其他模块在播放和停止音乐时进行干预。
 * 提供了播放音乐和停止当前音乐的功能。
 */
public class FPSClientMusicManager {

    static Minecraft mc = Minecraft.getInstance();
    private static final float FADE_DURATION_SECONDS = 1f; // 淡入淡出时长，单位秒
    private static Thread playThread;
    private static volatile boolean playing;
    private static ResourceLocation lastMusic = null;

    /**
     * 播放指定的音乐资源。
     * 
     * @param musicResource 音乐资源的 ResourceLocation
     */
    public static void playMusic(ResourceLocation musicResource) {
        SoundManager soundManager = mc.getSoundManager();
        if (musicResource != null) {
            stopMusic();
            SimpleSoundInstance instance = new SimpleSoundInstance(musicResource, SoundSource.VOICE, 1.0F, 1.0F, SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.LINEAR, 0.0D, 0.0D, 0.0D, true);
            soundManager.play(instance);
            lastMusic = musicResource;
            playing = true;
        } else {
            FPSMatch.LOGGER.error("failed to play music: music is null");
        }
    }

    public static void playSound(ResourceLocation musicResource) {
        SoundManager soundManager = mc.getSoundManager();
        if (musicResource != null) {
            SimpleSoundInstance instance = new SimpleSoundInstance(musicResource, SoundSource.VOICE, 1.0F, 1.0F, SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.LINEAR, 0.0D, 0.0D, 0.0D, true);
            soundManager.play(instance);
        } else {
            FPSMatch.LOGGER.error("failed to play sound: sound is null");
        }
    }

    /**
     * 播放指定的音乐事件。
     * <p>
     * 该方法会调用 {@link #playMusic(ResourceLocation)}，并传入音乐事件的资源路径。
     *
     * @param musicResource 音乐事件
     */
    public static void playMusic(SoundEvent musicResource) {
        playMusic(musicResource.getLocation());
    }

    public static void playSound(SoundEvent musicResource) {
        playSound(musicResource.getLocation());
    }

    /**
     * 开始播放音频流
     * 
     * @param music 音频
     */
    public static void play(OnlineMusic music) {
        stopMusic();

        playThread = new Thread(() -> {
            InputStream stream = music.stream();
            if (stream == null) return;
            try (BufferedInputStream bis = new BufferedInputStream(stream);
                    AudioInputStream ais = AudioSystem.getAudioInputStream(bis)) {

                AudioFormat format = ais.getFormat();
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

                try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
                    line.open(format);
                    float mcVolume = mc.options.getSoundSourceVolume(SoundSource.RECORDS);
                    float safeVolume = (mcVolume <= 0.0f) ? 0.0001f : mcVolume;

                    if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                        FloatControl volumeControl = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                        float dB = 20f * (float) Math.log10(safeVolume);
                        volumeControl.setValue(dB);
                    }
                    float sampleRate = format.getSampleRate();
                    int fadeInFrames = (int) (FADE_DURATION_SECONDS * sampleRate);
                    int fadeOutFrames = (int) (FADE_DURATION_SECONDS * sampleRate);

                    long totalFrames = ais.getFrameLength();

                    int frameSize = format.getFrameSize();

                    line.start();
                    playing = true;

                    byte[] buffer = new byte[4096];
                    long currentFrame = 0;

                    int bytesRead;
                    while (playing && (bytesRead = ais.read(buffer, 0, buffer.length)) != -1) {
                        int framesInChunk = bytesRead / frameSize;
                        for (int f = 0; f < framesInChunk; f++) {
                            long frameIndex = currentFrame + f;
                            float fadeFactor = 1.0f;

                            if (totalFrames > 0) {
                                long fadeOutStart = totalFrames - fadeOutFrames;
                                // 淡入
                                if (frameIndex < fadeInFrames) {
                                    fadeFactor = frameIndex / (float) fadeInFrames; // 0 ~ 1 线性上升
                                } else if (frameIndex > fadeOutStart) {
                                    long framesIntoFadeOut = frameIndex - fadeOutStart;
                                    float fraction = framesIntoFadeOut / (float) fadeOutFrames; // 0 ~ 1
                                    fadeFactor = 1.0f - fraction; // 1 ~ 0 线性下降
                                }
                            } else {
                                if (frameIndex < fadeInFrames) {
                                    fadeFactor = frameIndex / (float) fadeInFrames;
                                }
                            }
                            int frameOffsetBytes = f * frameSize;
                            for (int chByteOffset = 0; chByteOffset < frameSize; chByteOffset += 2) {
                                int sampleIndex = frameOffsetBytes + chByteOffset;
                                short sample = (short) ((buffer[sampleIndex + 1] << 8) | (buffer[sampleIndex] & 0xFF));
                                float processed = sample * fadeFactor;
                                short newSample = (short) Math.max(Short.MIN_VALUE,
                                        Math.min(Short.MAX_VALUE, (int) processed));
                                buffer[sampleIndex] = (byte) (newSample & 0xFF);
                                buffer[sampleIndex + 1] = (byte) ((newSample >> 8) & 0xFF);
                            }
                        }
                        currentFrame += framesInChunk;
                        line.write(buffer, 0, bytesRead);
                    }

                    line.drain();
                    line.stop();
                } catch (LineUnavailableException e) {
                    e.fillInStackTrace();
                }

            } catch (UnsupportedAudioFileException | IOException e) {
                e.fillInStackTrace();
            }
        }, "AudioPlayer-Thread");

        playThread.start();
    }

    /**
     * 停止当前播放的音乐。
     */
    public static void stopMusic() {
        if (playing) {
            mc.getMusicManager().stopPlaying();
            if (lastMusic != null) mc.getSoundManager().stop(lastMusic, SoundSource.VOICE);
        }

        playing = false;
        if (playThread != null && playThread.isAlive()) {
            playThread.interrupt();
        }
        playThread = null;
    }
}
