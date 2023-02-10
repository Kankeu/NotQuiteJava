int main(){
        A a;
        a = new A();
        a.a = 1;
        printInt(a.a); // field "a" of class "A" should contain 1
        B b;
        b = a;
        printInt(b.a); // field "a" of class "B" should contain 0
        return 0;
}

class A extends B{
    int a;
}


class B{
    int a;
}