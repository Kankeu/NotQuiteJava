int main(){
    return 0;
}
class A {
    boolean a;
    A dosomething(){
        int a;
        a = true; // "a" is of type int because of shadowing
        return this;
    }
}