package com.cyclone.mobile.runtime.background;
import android.os.Bundle;
import android.view.Surface;
interface IWorkspaceService {
    Bundle create(String sessionId, in Surface surface, int width, int height, int density) = 0;
    Bundle launch(String sessionId, String component) = 1;
    Bundle status(String sessionId) = 2;
    Bundle input(String sessionId, long generation, int kind, in float[] coordinates, String text) = 3;
    Bundle revoke(String sessionId) = 4;
    Bundle resume(String sessionId) = 5;
    Bundle handoff(String sessionId) = 6;
    void close(String sessionId) = 7;
    Bundle adopt(String sessionId, String packageName) = 8;
    Bundle mainTask(String packageName) = 9;
    Bundle view(String sessionId, String uri) = 10;
    Bundle launchSecond(String sessionId, String component) = 11;
    void destroy() = 16777114;
}
