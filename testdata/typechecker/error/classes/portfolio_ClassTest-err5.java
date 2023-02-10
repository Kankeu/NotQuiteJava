int main() {
        return 0;
}

class B extends A {
    int print(){ // TE
        return 1;
    } // incompatible signatures
}
class A{
    int print(int a){
        return 2;
    }
}