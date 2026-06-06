module test_tuple {
    @Inject ecstasy.io.Console console;
    
    typedef Tuple<A,B> as Pair<A,B>;
    typedef Pair<A,A> as Twin<A>;
    
    void run() {
        Twin<Int> t = (5, 3);
        console.print(t[0]);
        console.print(t[1]);
        
        Int a = t[0];
        Int b = t[1];
    }
}
