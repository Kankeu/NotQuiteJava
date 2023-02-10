int main() {
        return 0;
}
// cyclic inheritance
class A extends B { //TE
    int print(){
        return 1;
    }
}

class B extends A { //TE
    int print2(){
        return 2;
    }
}
