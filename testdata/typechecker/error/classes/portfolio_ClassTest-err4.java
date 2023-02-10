int main(){
        A a;
        a = new A();
        a.b = 1; // TE
        return 0;
}
class A extends B{
    boolean b; // hides field "b" of "B"
}

class B{
    int b;
}