package com.kar.mediaservice.utils;

import com.google.android.exoplayer.chunk.Format;
import com.google.android.exoplayer.chunk.FormatEvaluator;
import com.google.android.exoplayer.chunk.MediaChunk;

import java.util.List;

/**
 * Created by Karthik on 5/6/2016.
 */
public class MaxFixedEvaluator implements FormatEvaluator {

    private long mMaxBitrate;

    public MaxFixedEvaluator(long bitrate) {
        mMaxBitrate = bitrate;
    }

    @Override
    public void enable() {
        // Do nothing.
    }

    @Override
    public void disable() {
        // Do nothing.
    }

    @Override
    public void evaluate(List<? extends MediaChunk> queue, long playbackPositionUs,
                         Format[] formats, FormatEvaluator.Evaluation evaluation) {
        evaluation.format = formats[0];
        for (int i = 0; i < formats.length; i++) {
            Format format = formats[i];
            if (format.bitrate <= mMaxBitrate) {
                evaluation.format = format;
                break;
            }
        }
    }

}
