package dev.grxm.teams;

/** Injected on each BankSavedData instance; never a process-global state carrier. */
public interface TeamDataAccess {
    String grxmteams$getJson();
    String grxmteams$getError();
    void grxmteams$setJson(String json);
}
