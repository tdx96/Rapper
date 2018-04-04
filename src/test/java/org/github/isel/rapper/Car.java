package org.github.isel.rapper;

public class Car implements DomainObject<Car.PrimaryPk> {

    @EmbeddedId
    private final PrimaryPk pk;

    public Car(int owner, String plate, String brand, String model) {
        this.pk = new PrimaryPk(owner, plate);
        this.brand = brand;
        this.model = model;
    }

    private final String brand;
    private final String model;



    @Override
    public PrimaryPk getIdentityKey() {
        return null;
    }

    @Override
    public long getVersion() {
        return 0;
    }

    public static class PrimaryPk{
        private final int owner;

        public PrimaryPk(int owner, String plate) {
            this.owner = owner;
            this.plate = plate;
        }

        private final String plate;
    }
}