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

package com.morlunk.jumble;

import com.morlunk.jumble.model.Channel;
import com.morlunk.jumble.model.User;

interface IJumbleObserver {
    // Connection
    void onConnected();
    void onDisconnected();
    void onConnectionError(String message, boolean reconnecting);

    // Channel
    void onChannelAdded(out Channel channel);
    void onChannelStateUpdated(out Channel channel);
    void onChannelRemoved(out Channel channel);

    // User
    void onUserConnected(out User user);
    void onUserStateUpdated(out User user);
    void onUserRemoved(out User user);

    // Logging & Messaging
    void onLogInfo(String message);
    void onLogWarning(String message);
    void onMessageReceived(String message);
}