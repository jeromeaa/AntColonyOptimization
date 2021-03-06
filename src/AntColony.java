import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.Semaphore;

public class AntColony {

    private double evaporation=0.9999;
    private int epochNum=7;

    //number of ants (threads)
    public static int antNum;

    private double a=1;
    private double b=1;

    public static double alpha;
    public static double beta;

    // new trail deposit coefficient;
    private double Weight = 200;

    double thresh = 100;
    double step=0.5;

    //critical section
    public double[] bestRoute= new double[7];
    public double bestValue=Double.POSITIVE_INFINITY;
    public ArrayList bestHistory= new ArrayList<Double>();
    public int historySize=6;

    public int bestCounter=0;

    public static double[] tarifs;
    public static double[] powerNeeded;
    public static double Bmin=280;
    public static double Bmax=13720;
    public static double Qmin=-750;
    public static double Qmax=511.5;

    //Qi: cost/length of every road
    private double[] Qi = null;
    //Ti: pheromone coefficient on roads
    private double[][] Ti = null;//critical common data

    private Random rand = new Random();
    //ReaderWriter
    int numReaders = 0;
    Semaphore readLock = new Semaphore(1);
    Semaphore writeLock = new Semaphore(1);

    public void startRead() {
        try {
            readLock.acquire();
            numReaders++;
            if (numReaders == 1) writeLock.acquire();
            readLock.release();
        } catch (InterruptedException e) { e.printStackTrace(); }
    }
    public void endRead() {
        try {
            readLock.acquire();
            numReaders--;
            if (numReaders == 0) writeLock.release();
            readLock.release();
        } catch (InterruptedException e) { e.printStackTrace(); }
    }
    public void startWrite() {
        try {
            writeLock.acquire();
        } catch (InterruptedException e) { e.printStackTrace(); }
    }
    public void endWrite() {
        writeLock.release();
    }

    private void Instantiate(){
        //Linspace
        //
        int size=(int)Math.round((Qmax-Qmin)/step);
        size+=1;
        Qi= new double[size];
        Ti= new double[size][epochNum-1];
        double val = Qmin;
        for (int i=0;i<size;i++){
            Qi[i]=val;
            for (int j = 0; j<epochNum-1; j++){
                Ti[i][j]=1;
            }
            val+=step;
        }
    }

    private class Ant extends Thread{
        //path taken
        public int[] path = new int[epochNum-1];
        //Current epoch
        private int currentLocation=0;
        //probabilities of next epoch
        private double[] probs = null;
        //Has entered the discharge phase?
        private boolean discharge=false;
        //Oi Elements
        private double[] Oi= new double[epochNum];
        //Intermediate Variable Bi
        private double B=Bmin;

        private int newBest=1;

        public void reset(){
            probs= new double[Qi.length];
            currentLocation=0;
            discharge=false;
            newBest=1;
            B=Bmin;
        }

        private void probCalc() {

            //reader
            startRead();
            double[][] TiCopy=Ti.clone();
            endRead();
            // end reader
            double denom = 0.0;
            double[] p=new double[Qi.length];
            double pNeed=0;
            //check how much power is still needed
            for(int i=currentLocation; i<epochNum;i++){
                pNeed+=powerNeeded[i];
            }
            for (int l = 0; l < Qi.length; l++){
                //conditions
                if(Qi[l]+B<Bmin || Qi[l]+B>Bmax ||(discharge && Qi[l]>0) ||(Qi[l]<-powerNeeded[currentLocation]/0.95) || (Qi[l]>0 && Qi[l]+B-Bmin>pNeed/0.95))
                    p[l]=0;
                else p[l]= pow(TiCopy[l][currentLocation], a)* pow(1.0 / (Qi[l]-Qi[0]+1), b); //I remove the lowest value in order to remove negative values
                denom+=p[l];
            }

            for (int j = 0; j < Qi.length; j++) {
                probs[j] = p[j] / denom;
            }
        }

        private int selectNext(){
            // calculate probabilities for each choice (stored in probs)
            probCalc();
            // randomly select according to probs
            double r = rand.nextDouble();
            double tot = 0;
            for (int i = 0; i < Qi.length; i++) {
                tot += probs[i];
                if (tot >= r)
                    return i;
            }

            throw new RuntimeException("Not supposed to get here." + currentLocation);
        }

        public void takeRoute(int choice){
            path[currentLocation] = choice;
            B+=Qi[choice];
            if(Qi[choice]<0 && !discharge)
                discharge=true;
            currentLocation++;
        }

        public void getOi(){
            double amount=0;
            for (int i=0;i<epochNum-1;i++) {
                if (Qi[path[i]] < 0) {
                    Oi[i] = powerNeeded[i] + Qi[path[i]] * 0.95;
                    if (Oi[i] < 0)
                        Oi[i] = 0; //For safety
                } else Oi[i] = powerNeeded[i] + Qi[path[i]] / 0.93;
                amount+=Qi[path[i]];
            }

            if ((Oi[epochNum-1]=powerNeeded[epochNum-1]-amount*0.95)<0)
                Oi[epochNum-1]=0;
        }

        public double getObjective(){
            double cost=0;
            double peak=0;

            for(int cnt=0; cnt<epochNum;cnt++){
                if(Oi[cnt]>peak)
                    peak=Oi[cnt];
                cost+=Oi[cnt]*tarifs[cnt];
            }

            return cost*alpha+peak*beta;

        }

        private void updateTi(){

            // evaporation
            for (int i = 0; i < epochNum-1; i++)
                for (int j = 0; j < Ti.length; j++)
                    Ti[j][i] *= evaporation;

            // ant contribution

            int radius = 5;
            double contribution = Weight / getObjective();
            for (int i = 0; i < epochNum-1; i++){
                for (int j = -radius; j<radius; j++){
                    if(j!=0){
                        if(path[i]+j<Ti.length && path[i]+j>0)
                            Ti[path[i]+j][i] += contribution/Math.abs(j);
                    }
                    else Ti[path[i]+j][i] += newBest*1.2*contribution;
                }

            }

        }

        private void updateBest() {

            double val = getObjective()-bestValue;
            if (val<0) {
                newBest=2;
                double amount =0;
                for(int i=0; i<epochNum-1;i++){
                    bestRoute[i]=Qi[path[i]];
                    amount += Qi[path[i]];
                }
                bestRoute[epochNum-1]=-amount;
                bestCounter=0;
                bestValue = getObjective();

                bestHistory.add(bestValue);
                if(bestHistory.size()>historySize){
                    bestHistory.remove(0);
                    double v=(Double)bestHistory.get(0)- bestValue;
                    if(v<thresh)
                        thresh=v;
                }
                System.out.println("the newest best value is:" + bestValue);
                System.out.println(bestRoute[0]+","+bestRoute[1]+","+bestRoute[2]+","+bestRoute[3]+","+bestRoute[4]+","+bestRoute[5]+","+bestRoute[6]);
            }else if(val <2){
                newBest=2;
                bestCounter++;

                bestHistory.add(bestValue);
                if(bestHistory.size()>historySize){
                    bestHistory.remove(0);
                    double v=(Double)bestHistory.get(0)- bestValue;
                    if(v<thresh)
                        thresh=v;
                }
                System.out.println("+");
            }
        }

        public void run(){
            do {
                reset();
                for (int i = 0; i < epochNum-1; i++) {
                    takeRoute(selectNext());
                }
                //System.out.println(Qi[path[0]]+","+Qi[path[1]]+","+Qi[path[2]]+","+Qi[path[3]]+","+Qi[path[4]]+","+Qi[path[5]]+",");
                getOi();
                //critical section
                startWrite();

                if(getObjective()-thresh<bestValue){
                    updateBest();
                    updateTi();
                }
                endWrite();
                //end of critical section
            }while (bestCounter<5);
        }
    }
    // Important facts:
    // - >25 times faster
    // - Extreme cases can lead to error of 25% - but usually less.
    // - Does not harm results -- not surprising for a stochastic algorithm.
    public static double pow(final double a, final double b) {
        final int x = (int) (Double.doubleToLongBits(a) >> 32);
        final int y = (int) (b * (x - 1072632447) + 1072632447);
        return Double.longBitsToDouble(((long) y) << 32);
    }

    public void MyAntColony(){

        Instantiate();

        Ant[] workers = new Ant[antNum];
        
        for(int i =0; i<antNum; i++){
            workers[i]= new Ant();
            workers[i].start();
        }
        
        try {
            for (Ant ant:workers) {
                ant.join();
            }
        } catch ( InterruptedException e ) { }

        System.out.println("THE BEST VALUE IS:"+bestValue);
        System.out.println("Resulting Qi are:" + Arrays.toString(bestRoute));
    }

    public static class Demo{
        public static void main(String[] args){
            //reading from text file

            File file = new File("src/java.txt");
            try {
                Scanner sc = new Scanner(file);
                double[] p =new double[17];
                int i=0;
                while (sc.hasNextDouble()){

                    p[i]=sc.nextDouble();
                    i++;
                }
                System.out.println(Arrays.toString(p));
                powerNeeded=Arrays.copyOfRange(p,0,7);
                tarifs= Arrays.copyOfRange(p,7,14);
                alpha= p[14];
                beta= p[15];
                antNum=(int)p[16];
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
            AntColony myColony = new AntColony();
            myColony.MyAntColony();
        }
    }

}