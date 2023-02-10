int main(){
        A a;
        a = new A();
        printInt(a.a);
        printInt(a.b);
        printInt(a.print());
        printInt(a.pop());
        return 0;
}
// attributes and methods of superclass "B" should be accessible from subclass "A"
class A extends B{
    int a;
    int print(){
        return 1;
    }
}


class B{
    int b;
    int pop(){
        return 0;
    }
}