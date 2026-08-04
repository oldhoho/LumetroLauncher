package ru.queuejw.lumetro.components.freeform.helper;

public class FreeformHackHelper {

    private boolean freeformHackActive = false;
    private boolean inFreeformWorkspace = false;

    private static FreeformHackHelper theInstance;

    private FreeformHackHelper() {}

    public static FreeformHackHelper getInstance() {
        if(theInstance == null) theInstance = new FreeformHackHelper();
        return theInstance;
    }

    public boolean isFreeformHackActive() {
        return freeformHackActive;
    }

    public void setFreeformHackActive(boolean value) {
        freeformHackActive = value;
    }

    public boolean isInFreeformWorkspace() {
        return inFreeformWorkspace;
    }

    public void setInFreeformWorkspace(boolean value) {
        inFreeformWorkspace = value;
    }

    // 重置所有状态（退出工作台时调用）
    public void reset() {
        freeformHackActive = false;
        inFreeformWorkspace = false;
    }
}
