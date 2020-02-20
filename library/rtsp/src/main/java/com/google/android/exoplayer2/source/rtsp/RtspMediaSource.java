/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.source.rtsp;

import android.net.Uri;
import android.os.Handler;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayerLibraryInfo;
import com.google.android.exoplayer2.drm.DrmSession;
import com.google.android.exoplayer2.drm.DrmSessionManager;
import com.google.android.exoplayer2.source.BaseMediaSource;
import com.google.android.exoplayer2.source.MediaPeriod;
import com.google.android.exoplayer2.source.MediaSourceEventListener;
import com.google.android.exoplayer2.source.MediaSourceEventListener.EventDispatcher;
import com.google.android.exoplayer2.source.SinglePeriodTimeline;
import com.google.android.exoplayer2.source.rtsp.core.Client;
import com.google.android.exoplayer2.source.rtsp.media.MediaType;
import com.google.android.exoplayer2.upstream.Allocator;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.util.Assertions;

import java.io.IOException;

import static com.google.android.exoplayer2.C.TCP;

public final class RtspMediaSource extends BaseMediaSource implements Client.EventListener {

    static {
        ExoPlayerLibraryInfo.registerModule("goog.exo.rtsp");
    }

    /** Factory for {@link RtspMediaSource}. */
    public static final class Factory {
        private boolean isLive;
        private boolean isCreateCalled;
        private DrmSessionManager<?> drmSessionManager;

        private final Client.Factory<? extends Client> factory;

        /**
         * Creates a factory for {@link RtspMediaSource}s.
         *
         * @param factory The factory from which read the media will
         *     be obtained.
         */
        public Factory(Client.Factory<? extends Client> factory) {
            this.factory = Assertions.checkNotNull(factory);
            drmSessionManager = DrmSessionManager.getDummyDrmSessionManager();
        }

        public Factory setIsLive(boolean isLive) {
            Assertions.checkState(!isCreateCalled);
            this.isLive = isLive;
            return this;
        }

        /**
         * Sets the {@link DrmSessionManager} to use for acquiring {@link DrmSession DrmSessions}. The
         * default value is {@link DrmSessionManager#DUMMY}.
         *
         * @param drmSessionManager The {@link DrmSessionManager}.
         * @return This factory, for convenience.
         * @throws IllegalStateException If one of the {@code create} methods has already been called.
         */
        public Factory setDrmSessionManager(DrmSessionManager<?> drmSessionManager) {
            Assertions.checkState(!isCreateCalled);
            this.drmSessionManager = drmSessionManager;
            return this;
        }

        /**
         * Returns a new {@link RtspMediaSource} using the current parameters. Media source events
         * will not be delivered.
         *
         * @param uri The {@link Uri}.
         * @return The new {@link RtspMediaSource}.
         */
        public RtspMediaSource createMediaSource(Uri uri) {
            isCreateCalled = true;
            return new RtspMediaSource(uri, factory, isLive, drmSessionManager);
        }

        /**
         * Returns a new {@link RtspMediaSource} using the current parameters. Media source events
         * will not be delivered.
         *
         * @param uri The {@link Uri}.
         * @param eventHandler A handler for events.
         * @param eventListener A listener of events.
         * @return The new {@link RtspMediaSource}.
         */
        public RtspMediaSource createMediaSource(Uri uri,
                                                 @Nullable Handler eventHandler,
                                                 @Nullable MediaSourceEventListener eventListener) {
            RtspMediaSource mediaSource = createMediaSource(uri);
            if (eventHandler != null && eventListener != null) {
                mediaSource.addEventListener(eventHandler, eventListener);
            }
            return mediaSource;
        }

    }

    private final Uri uri;
    private final Client.Factory<? extends Client> factory;
    private EventDispatcher eventDispatcher;

    private Client client;
    private boolean isLive;

    private @C.TransportProtocol
    int transportProtocol;

    private DrmSessionManager<?> drmSessionManager;
    private @Nullable TransferListener transferListener;
    private final RtspFallbackPolicy fallbackPolicy;

    private RtspMediaSource(Uri uri, Client.Factory<? extends Client> factory, boolean isLive,
        DrmSessionManager<?> drmSessionManager) {
        this.uri = uri;
        this.isLive = isLive;
        this.factory = factory;
        this.drmSessionManager = drmSessionManager;

        fallbackPolicy = new RtspFallbackPolicy(factory.getMode());
    }

    @Override
    public boolean isLive() {
        return isLive;
    }

    @Override
    public boolean isOnTcp() { return transportProtocol == TCP; }

    // MediaTrackSource implementation
    @Override
    public void maybeThrowSourceInfoRefreshError() throws IOException {
        if (client == null) {
            throw new IOException();
        }
    }

    @Override
    public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long startPositionUs) {
        eventDispatcher = createEventDispatcher(id);
        return new RtspMediaPeriod(this,
                client,
                transferListener,
                eventDispatcher,
                allocator,
                drmSessionManager);
    }

    @Override
    public void releasePeriod(MediaPeriod mediaPeriod) {
        ((RtspMediaPeriod) mediaPeriod).release();
    }

    @Override
    protected void prepareSourceInternal(@Nullable TransferListener transferListener) {
        this.transferListener = transferListener;

        drmSessionManager.prepare();
        eventDispatcher = createEventDispatcher(null);

        try {

            client = new Client.Builder(factory)
                .setUri(uri)
                .setListener(this)
                .setPlayer(getPlayer())
                .setFallbackPolicy(fallbackPolicy)
                .build();

            client.open();

        } catch (IOException e) {
            eventDispatcher.loadError(
                new DataSpec(uri), uri, null, C.DATA_TYPE_MEDIA_INITIALIZATION,
                0, 0, 0, e, false);
        }
    }

    @Override
    public void releaseSourceInternal() {
        drmSessionManager.release();
        if (client != null) {
            client.release();
            client = null;
        }
    }

    // Client.EventListener implementation
    @Override
    public void onMediaDescriptionInfoRefreshed(long durationUs) {
        refreshSourceInfo(new SinglePeriodTimeline(durationUs,
            durationUs != C.TIME_UNSET, false, isLive));
    }

    @Override
    public void onMediaDescriptionTypeUnSupported(MediaType mediaType) {
        if (eventDispatcher != null) {
            eventDispatcher.loadError(new DataSpec(uri), uri, null, C.DATA_TYPE_MANIFEST,
                0, 0, 0,
                    new IOException("Media Description Type [" + mediaType + "] is not supported"),
                    false);
        }
    }

    @Override
    public void onTransportProtocolNegotiated(@C.TransportProtocol int protocol) {
        transportProtocol = protocol;
    }

    @Override
    public void onClientError(Throwable throwable) {
        if (eventDispatcher != null) {
            eventDispatcher.loadError(new DataSpec(uri), uri, null, C.DATA_TYPE_MEDIA,
                0, 0, 0, (IOException) throwable, false);
        }
    }
}
