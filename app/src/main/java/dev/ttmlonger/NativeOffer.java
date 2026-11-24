package dev.ttmlonger;

/** Counter retained for diagnostics; the save action is now in the native share sheet. */
final class NativeOffer {
    private NativeOffer() {}
    static int shown() { return StoryShare.videoActions(); }
    static void maybe(Object video) { /* No floating offer. */ }
}
