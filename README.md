# Blood Pressure Monitoring — Android BLE Application

## Description

Cette application Android, développée sous Android Studio en Java, permet d’interagir avec un périphérique BLE simulé implémentant le service **Blood Pressure (0x1810)**.

L’objectif principal est de :
- scanner des périphériques BLE
- se connecter à un serveur GATT (émulateur)
- lire et interpréter des mesures de pression artérielle
- récupérer des enregistrements via le protocole RACP

---

## Environnement de test

Le projet repose sur :

- Application Android (Java)
- Émulateur BLE basé sur **nRF52 (Nordic Semiconductor)**
- Configuration du serveur GATT via **nRF Connect**


---

## Service BLE utilisé

### Blood Pressure Service (UUID : 0x1810)

L’application interagit avec les caractéristiques suivantes :

---

### Blood Pressure Measurement
- UUID : `0x2A35`
- Propriété : **Indicate**
- Descriptor :
  - CCCD (0x2902) → Read + Write

Fonction :
- Réception des mesures en temps réel
- Données décodées :
  - pression systolique
  - pression diastolique
  - pression moyenne 
  - pouls 
  - timestamp 

---

### Blood Pressure Feature
- UUID : `0x2A49`
- Propriété : **Read**

Fonction :
- Lecture des capacités du capteur
- Exemples :
  - détection de mouvement
  - détection de pouls irrégulier
  - multi-utilisateur

---

### Blood Pressure Record
- UUID : `0x2B36`
- Propriété : **Notify**
- Descriptor :
  - CCCD (0x2902)

Fonction :
- Réception des enregistrements stockés
- Chaque enregistrement contient :
  - un header (segmentation, compteur, UUID)
  - un payload de type measurement (2A35)

---

### RACP (Record Access Control Point)
- UUID : `0x2A52`
- Propriété : **Write + Indicate**

Fonction :
- Gestion des enregistrements

Commandes utilisées :
- `0x01 0x01` → récupérer tous les enregistrements
- `0x04 0x01` → compter les enregistrements
- `0x03 0x00` → annuler l’opération

---

## Fonctionnement de l’application

### 1. Scan BLE
- Scan des périphériques pendant 10 secondes
- Affichage dans une liste

### 2. Connexion GATT
- Connexion au device sélectionné
- Découverte des services

### 3. Configuration
- Activation des indications (Measurement, RACP)
- Activation des notifications (Record)
- Lecture de la caractéristique Feature

### 4. Réception et traitement des données

#### Measurement (2A35)
- Décodage du format IEEE 11073
- Affichage :
  - tension (SYS / DIA)
  - pouls
  - date

#### Record (2B36)
- Parsing des enregistrements
- Extraction du payload
- Décodage comme une measurement

#### RACP
- Interprétation des réponses :
  - succès
  - nombre d’enregistrements
  - statut

---

## Gestion des opérations BLE

Android impose une exécution séquentielle des opérations GATT.

L’application utilise une **file d’attente (queue)** pour :
- writeDescriptor
- writeCharacteristic
- readCharacteristic

Principe :
- une seule opération à la fois
- lancement de la suivante après callback



---

## Structure du projet

### MainActivity.java

Fichier principal contenant :
- gestion des permissions Bluetooth
- scan BLE
- connexion GATT
- gestion des caractéristiques
- parsing des données

---

## Interface utilisateur

L’application permet :
- d’activer le Bluetooth
- de scanner les périphériques
- de se connecter à un device
- d’envoyer des commandes RACP :
  - récupérer les enregistrements
  - compter les enregistrements
  - annuler une opération
- d’afficher :
  - les mesures en temps réel
  - les enregistrements

---

