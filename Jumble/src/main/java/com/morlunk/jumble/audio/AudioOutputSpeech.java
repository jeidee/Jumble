/*
 * Copyright (C) 2013 Andrew Comminos
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.morlunk.jumble.audio;

import com.morlunk.jumble.audio.celt.CELT;
import com.morlunk.jumble.audio.celt.SWIGTYPE_p_CELTDecoder;
import com.morlunk.jumble.audio.opus.Opus;
import com.morlunk.jumble.audio.opus.SWIGTYPE_p_OpusDecoder;
import com.morlunk.jumble.audio.speex.JitterBufferPacket;
import com.morlunk.jumble.audio.speex.SWIGTYPE_p_JitterBuffer_;
import com.morlunk.jumble.audio.speex.SWIGTYPE_p_void;
import com.morlunk.jumble.audio.speex.Speex;
import com.morlunk.jumble.audio.speex.SpeexBits;
import com.morlunk.jumble.audio.speex.SpeexConstants;
import com.morlunk.jumble.net.JumbleUDPMessageType;
import com.morlunk.jumble.net.PacketDataStream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Created by andrew on 16/07/13.
 */
public class AudioOutputSpeech {
    // Native audio pointers
    private SWIGTYPE_p_OpusDecoder mOpusDecoder;
    private SWIGTYPE_p_CELTDecoder mCELTDecoder;
    private SWIGTYPE_p_void mSpeexDecoder;
    private SpeexBits mSpeexBits;
    private SWIGTYPE_p_JitterBuffer_ mJitterBuffer;

    private int mSession;
    private JumbleUDPMessageType mCodec;
    private int mAudioBufferSize = Audio.FRAME_SIZE;

    // State-specific
    private float[] mBuffer;
    private Queue<byte[]> mFrames = new ConcurrentLinkedQueue<byte[]>();
    private int mMissCount = 0;
    private int mMissedFrames = 0;
    private float mAverageAvailable = 0;
    private boolean mHasTerminator = false;
    private boolean mLastAlive = true;
    private int mBufferOffset, mBufferFilled, mLastConsume = 0;

    public AudioOutputSpeech(int session, JumbleUDPMessageType codec) {
        // TODO: consider implementing resampling if some Android devices not support 48kHz?
        mSession = session;
        mCodec = codec;
        int error = 0;
        switch (codec) {
            case UDPVoiceOpus:
                mAudioBufferSize *= 12;
                mOpusDecoder = Opus.opus_decoder_create(Audio.SAMPLE_RATE, 1, new int[] { error });
                break;
            case UDPVoiceSpeex:
                Speex.speex_bits_init(mSpeexBits);
                mSpeexDecoder = Speex.speex_decoder_init(Speex.getSpeex_uwb_mode());
                Speex.speex_decoder_ctl(mSpeexDecoder, SpeexConstants.SPEEX_SET_ENH, new int[] { 1 });
                break;
            case UDPVoiceCELTBeta:
            case UDPVoiceCELTAlpha:
                mCELTDecoder = CELT.celt_decoder_create(Audio.SAMPLE_RATE, 1, new int[] { error });
                break;
        }

        mJitterBuffer = Speex.jitter_buffer_init(Audio.FRAME_SIZE);
        int margin = 10 * Audio.FRAME_SIZE;
        Speex.jitter_buffer_ctl(mJitterBuffer, SpeexConstants.JITTER_BUFFER_SET_MARGIN, new int[] { margin });
    }

    public void addFrameToBuffer(byte[] data, int seq) {
        if(data.length < 2)
            return;

        PacketDataStream pds = new PacketDataStream(data, data.length);

        // skip flags
        pds.next();

        int samples = 0;
        if(mCodec == JumbleUDPMessageType.UDPVoiceOpus) {
            int size = pds.next();
            size &= 0x1fff;

            byte[] packet = pds.dataBlock(size);
            int frames = Opus.opus_packet_get_nb_frames(packet, size);
            samples = Opus.opus_packet_get_samples_per_frame(packet, Audio.FRAME_SIZE);

            if(samples % Audio.FRAME_SIZE == 0)
                return; // We can't handle frames which are not a multiple of 10ms.
        } else {
            int header;
            do {
                header = pds.next();
                samples += Audio.FRAME_SIZE;
                pds.skip(header & 0x7f);
            } while ((header & 0x80) > 0 && pds.isValid());
        }

        if(pds.isValid()) {
            JitterBufferPacket jbp = new JitterBufferPacket();
            jbp.setData(data);
            jbp.setLen(data.length);
            jbp.setSpan(samples);
            jbp.setTimestamp(Audio.FRAME_SIZE * seq);
            synchronized(mJitterBuffer) {
                Speex.jitter_buffer_put(mJitterBuffer, jbp);
            }
        }
    }

    public boolean needSamples(int num) {
        for(int i = mLastConsume; i < mBufferFilled; i++)
            mBuffer[i-mLastConsume] = mBuffer[i];
        mBufferFilled -= mLastConsume;

        mLastConsume = num;

        if(mBufferFilled >= num)
            return mLastAlive;

        float[] out = new float[mAudioBufferSize];
        boolean nextAlive = mLastAlive;

        while(mBufferFilled < num) {
            int decodedSamples = Audio.FRAME_SIZE;
            resizeBuffer(mBufferFilled + mAudioBufferSize);
            System.arraycopy(mBuffer, mBufferFilled, out, 0, mAudioBufferSize);

            if(!mLastAlive)
                Arrays.fill(out, 0);
            else {
                int avail = 0;
                int ts = Speex.jitter_buffer_get_pointer_timestamp(mJitterBuffer);
                Speex.jitter_buffer_ctl(mJitterBuffer, SpeexConstants.JITTER_BUFFER_GET_AVAILABLE_COUNT, new int[] { avail });

                if(ts != 0) {
                    int want = (int) mAverageAvailable;
                    if (avail < want) {
                        mMissCount++;
                        if(mMissCount < 20) {
                            Arrays.fill(out, 0);
                            mBufferFilled += mAudioBufferSize;
                            continue;
                        }
                    }
                }

                if(mFrames.isEmpty()) {
                    byte[] data = new byte[4096];
                    JitterBufferPacket jbp = new JitterBufferPacket();
                    jbp.setData(data);
                    jbp.setLen(4096);

                    int startofs = 0;
                    int result = 0;

                    synchronized (mJitterBuffer) {
                        result = Speex.jitter_buffer_get(mJitterBuffer, jbp, Audio.FRAME_SIZE, new int[] { startofs });
                    }

                    if(result == SpeexConstants.JITTER_BUFFER_OK) {
                        PacketDataStream pds = new PacketDataStream(jbp.getData(), (int)jbp.getLen());

                        mMissCount = 0;
                        byte flags = (byte)pds.next();

                        mHasTerminator = false;
                        if(mCodec == JumbleUDPMessageType.UDPVoiceOpus) {
                            int size = pds.next();

                            mHasTerminator = (size & 0x2000) > 0;
                            mFrames.add(pds.dataBlock(size & 0x1fff));
                        } else {
                            int header;
                            do {
                                header = pds.next() & 0xFF;
                                if(header > 0)
                                    mFrames.add(pds.dataBlock(header & 0x7f));
                                else
                                    mHasTerminator = true;
                            } while ((header & 0x80) > 0 && pds.isValid());
                        }
                    } else {
                        synchronized (mJitterBuffer) {
                            Speex.jitter_buffer_update_delay(mJitterBuffer, jbp, null);
                        }

                        mMissCount++;
                        if(mMissCount > 10)
                            nextAlive = false;
                    }
                }

                if(!mFrames.isEmpty()) {
                    byte[] data = mFrames.poll();

                    if(mCodec == JumbleUDPMessageType.UDPVoiceCELTAlpha || mCodec == JumbleUDPMessageType.UDPVoiceCELTBeta) {
                        // TODO handle both CELT alpha and CELT beta codecs
                        CELT.celt_decode_float(mCELTDecoder,
                                mFrames.isEmpty() ? null : data,
                                data.length,
                                out,
                                Audio.FRAME_SIZE);
                    } else if(mCodec == JumbleUDPMessageType.UDPVoiceOpus) {
                        decodedSamples = Opus.opus_decode_float(mOpusDecoder,
                                mFrames.isEmpty() ? null : data,
                                data.length,
                                out,
                                mAudioBufferSize,
                                0);
                    } else { // Speex
                        if(mFrames.isEmpty())
                            Speex.speex_decode(mSpeexDecoder, null, out);
                        else {
                            Speex.speex_bits_read_from(mSpeexBits, data, data.length);
                            Speex.speex_decode(mSpeexDecoder, mSpeexBits, out);
                        }
                        for(int i = 0; i < Audio.FRAME_SIZE; i++)
                            out[i] *= (1.0f / 32767.f);
                    }


                    if(mFrames.isEmpty())
                        synchronized (mJitterBuffer) {
                            Speex.jitter_buffer_update_delay(mJitterBuffer, null, null);
                        }

                    if(mFrames.isEmpty() && mHasTerminator)
                        nextAlive = false;
                } else {
                    if(mCodec == JumbleUDPMessageType.UDPVoiceCELTAlpha || mCodec == JumbleUDPMessageType.UDPVoiceCELTBeta)
                        CELT.celt_decode_float(mCELTDecoder, null, 0, out, Audio.FRAME_SIZE);
                    else if(mCodec == JumbleUDPMessageType.UDPVoiceOpus)
                        decodedSamples = Opus.opus_decode_float(mOpusDecoder, null, 0, out, Audio.FRAME_SIZE, 0);
                    else {
                        Speex.speex_decode(mSpeexDecoder, null, out);
                        for(int i = 0; i < Audio.FRAME_SIZE; i++)
                            out[i] *= (1.0f / 32767.f);
                    }
                }

                for(int i = decodedSamples / Audio.FRAME_SIZE; i > 0; i--)
                    synchronized (mJitterBuffer) {
                        Speex.jitter_buffer_tick(mJitterBuffer);
                    }
            }

            mBufferFilled += mAudioBufferSize;
        }

        // TODO set talkstate

        boolean tmp = mLastAlive;
        mLastAlive = nextAlive;
        return tmp;
    }

    public void resizeBuffer(int newSize) {
        if(newSize > mAudioBufferSize) {
            float[] n = new float[newSize];
            if(mBuffer != null)
                System.arraycopy(mBuffer, 0, n, 0, mAudioBufferSize);
            mBuffer = n;
            mAudioBufferSize = newSize;
        }
    }

    public float[] getBuffer() {
        return mBuffer;
    }

    /**
     * Cleans up all JNI refs linked to this instance.
     * This MUST be called eventually, otherwise we get memory leaks!
     */
    public void destroy() {
        if(mOpusDecoder != null)
            Opus.opus_decoder_destroy(mOpusDecoder);
        if(mCELTDecoder != null)
            CELT.celt_decoder_destroy(mCELTDecoder);
        if(mSpeexBits != null)
            Speex.speex_bits_destroy(mSpeexBits);
        if(mSpeexDecoder != null)
            Speex.speex_decoder_destroy(mSpeexDecoder);
        if(mJitterBuffer != null)
            Speex.jitter_buffer_destroy(mJitterBuffer);
    }
}