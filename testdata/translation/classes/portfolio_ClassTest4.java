int main(){
        A[] as;
        as = new A[10];
        printInt(as[0].says()); // halt with an error because "as[0]" is null
        return 0;
}

class A {
    boolean is;
    A[] as;

    int says() {
        return 1;
    }
}


