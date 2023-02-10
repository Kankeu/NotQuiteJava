int main(){
        A a;
        a=new A();
        a = a.print(); // should return an object of type "A"
        return 0;
}

class A extends B {
    A print() {
        return this;
    }
}


class B {
    B print() {
        return this;
    }
}