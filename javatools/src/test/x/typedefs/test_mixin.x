module test_mixin {
    @Inject ecstasy.io.Console console;
    
    typedef Tuple<A,B> as Pair<A,B>;
    typedef Pair<A,A> as Twin<A>;
    
    mixin Xor into Twin<Int> {
        Int xor() {
            return this[0] ^ this[1];
        }
    }

    void run() {
        // Show that the mixin method dispatches at runtime
        Twin<Int> t = (5, 3);
        console.print("t[0]=");
        console.print(t[0]);
        console.print(" t[1]=");
        console.print(t[1]);
        console.print(" t[0] ^ t[1]=");
        console.print(t[0] ^ t[1]);

        console.print("xor=");
        console.print(t.xor());
    }
}
