int main(){
        A[] as;
        as = new A[2];
        as[0] = new A();
        as[1] = new A();
        as[0].a = 1;
        as[1].a = 2;
        printInt(as[0].a); // should print 1
        printInt(as[1].a); // should print 2

        return 0;
}

class A{
    int a;
}
