int main(){
        return 0;
}
class A{
    boolean a;
    boolean a; // duplicated field name
    A dosomething(){
        return this;
    }
    A dosomething(){ // duplicated method name
        return this;
    }
}